import dev.robocode.tankroyale.botapi.Bot;
import dev.robocode.tankroyale.botapi.BotInfo;
import dev.robocode.tankroyale.botapi.BulletState;
import dev.robocode.tankroyale.botapi.events.BulletHitBotEvent;
import dev.robocode.tankroyale.botapi.events.HitByBulletEvent;
import dev.robocode.tankroyale.botapi.events.HitWallEvent;
import dev.robocode.tankroyale.botapi.events.ScannedBotEvent;
import dev.robocode.tankroyale.botapi.graphics.Color;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * GHCopilotBot: adapts its target selection and movement to 1v1 and melee battles.
 * It cannot guarantee a win, but combines predictive aiming, learned dodging,
 * target prioritization, and wall-aware movement.
 */
public class GHCopilotBot extends Bot {
    private static final double HALF_SIZE = 18.0;
    private static final int BINS = 31;
    private static final int CENTER = BINS / 2;
    private static final int MAX_TRACK_AGE = 45;
    private static final int MAX_WAVES = 80;

    private static final class Enemy {
        final int id;
        double x, y, heading, speed, energy, turnRate;
        double pendingDamage;
        double myXAtScan, myYAtScan;
        int lastSeen = -100;

        Enemy(int id) {
            this.id = id;
        }
    }

    private static final class EnemyWave {
        double x, y, speed, directAngle, maxEscapeAngle;
        int fireTurn, lateralDirection;
    }

    private final Map<Integer, Enemy> enemies = new HashMap<>();
    private final List<EnemyWave> waves = new ArrayList<>();
    private final double[] danger = new double[BINS];

    private Enemy target;
    private int lastScanTurn = -100;
    private int orbitDirection = 1;
    private int lastLateralDirection = 1;

    public static void main(String[] args) {
        new GHCopilotBot().start();
    }

    GHCopilotBot() {
        super(BotInfo.builder()
                .setName("GHCopilotBot")
                .setVersion("3.0")
                .addAuthor("marshvmaro")
                .build());
    }

    @Override
    public void run() {
        setAdjustGunForBodyTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForBodyTurn(true);
        setBodyColor(Color.fromRgb(0, 170, 190));
        setTurretColor(Color.fromRgb(20, 45, 130));
        setRadarColor(Color.fromRgb(240, 240, 240));
        setBulletColor(Color.fromRgb(255, 170, 20));
        setMaxSpeed(8);
        orbitDirection = Math.random() < 0.5 ? 1 : -1;

        while (isRunning()) {
            int now = getTurnNumber();
            removeStaleEnemies(now);
            if (getEnemyCount() > 1 || now - lastScanTurn > 3) {
                // A broad sweep is important in melee so we keep a current map of opponents.
                setTurnRadarLeft(Double.POSITIVE_INFINITY);
            }
            updateWaves();
            selectTarget();
            move();
            go();
        }
    }

    @Override
    public void onScannedBot(ScannedBotEvent event) {
        int now = getTurnNumber();
        int id = event.getScannedBotId();
        Enemy enemy = enemies.get(id);
        if (enemy == null) {
            enemy = new Enemy(id);
            enemies.put(id, enemy);
        }

        double oldX = enemy.x;
        double oldY = enemy.y;
        double oldEnergy = enemy.energy;
        int elapsed = Math.max(1, now - enemy.lastSeen);
        if (enemy.lastSeen > -50) {
            enemy.turnRate = clamp(normalize(event.getDirection() - enemy.heading) / elapsed, -10, 10);
            double unexplainedDrop = oldEnergy - enemy.pendingDamage - event.getEnergy();
            if (elapsed <= 2 && unexplainedDrop >= 0.1 && unexplainedDrop <= 3.01) {
                addWave(enemy, oldX, oldY, unexplainedDrop, enemy.lastSeen);
            }
        } else {
            enemy.turnRate = 0;
        }

        enemy.x = event.getX();
        enemy.y = event.getY();
        enemy.heading = event.getDirection();
        enemy.speed = event.getSpeed();
        enemy.energy = event.getEnergy();
        enemy.pendingDamage = 0;
        enemy.lastSeen = now;
        enemy.myXAtScan = getX();
        enemy.myYAtScan = getY();
        lastScanTurn = now;

        double absoluteBearing = angleTo(getX(), getY(), enemy.x, enemy.y);
        double lateralVelocity = getSpeed() * Math.sin(Math.toRadians(getDirection() - absoluteBearing));
        if (Math.abs(lateralVelocity) > 0.1) {
            lastLateralDirection = lateralVelocity > 0 ? 1 : -1;
        }

        selectTarget();
        if (getEnemyCount() <= 1 && target == enemy) {
            // Sweep a little past the target to catch it again after it moves.
            double radarTurn = normalize(absoluteBearing - getRadarDirection());
            double overshoot = Math.toDegrees(2 * Math.atan(HALF_SIZE / Math.max(1, distanceTo(enemy.x, enemy.y)))) + 2;
            setTurnRadarLeft(radarTurn + Math.copySign(overshoot, radarTurn == 0 ? 1 : radarTurn));
        }
        if (target != null) aimAndFire(target);
    }

    /** Prefer a viable weak opponent in melee, but avoid repeatedly chasing distant stale scans. */
    private void selectTarget() {
        int now = getTurnNumber();
        Enemy best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Enemy enemy : enemies.values()) {
            int age = now - enemy.lastSeen;
            if (age > MAX_TRACK_AGE) continue;
            double predictedX = enemy.x + Math.cos(Math.toRadians(enemy.heading)) * enemy.speed * Math.min(age, 12);
            double predictedY = enemy.y + Math.sin(Math.toRadians(enemy.heading)) * enemy.speed * Math.min(age, 12);
            predictedX = clamp(predictedX, HALF_SIZE, getArenaWidth() - HALF_SIZE);
            predictedY = clamp(predictedY, HALF_SIZE, getArenaHeight() - HALF_SIZE);
            double distance = Math.hypot(predictedX - getX(), predictedY - getY());
            double score;
            if (getEnemyCount() <= 1) {
                score = distance;
            } else {
                // Low-energy bots are finishable; distance still matters to avoid wasteful long chases.
                score = distance + Math.max(0, enemy.energy) * 2.2 + age * 12;
                if (enemy.energy < 12) score -= 100;
            }
            if (score < bestScore) {
                bestScore = score;
                best = enemy;
            }
        }
        target = best;
    }

    private void aimAndFire(Enemy enemy) {
        double distance = Math.hypot(enemy.x - getX(), enemy.y - getY());
        double power;
        if (getEnemyCount() > 1) {
            power = distance < 180 ? 2.2 : distance < 400 ? 1.5 : 0.9;
        } else {
            power = distance < 150 ? 3.0 : distance < 350 ? 2.4 : distance < 600 ? 1.8 : 1.2;
        }
        if (getEnergy() < 20) power = Math.min(power, 1.2);
        if (getEnergy() < 8) power = Math.min(power, 0.7);
        double killPower = enemy.energy <= 4 ? enemy.energy / 4 : (enemy.energy + 2) / 6;
        power = Math.max(0.1, Math.min(power, Math.min(3.0, killPower + 0.05)));

        double bulletSpeed = 20 - 3 * power;
        double px = enemy.x;
        double py = enemy.y;
        double heading = enemy.heading;
        int flightTime = 1;
        for (; flightTime <= 100; flightTime++) {
            heading = normalize(heading + enemy.turnRate);
            px += Math.cos(Math.toRadians(heading)) * enemy.speed;
            py += Math.sin(Math.toRadians(heading)) * enemy.speed;
            boolean hitVerticalWall = px < HALF_SIZE || px > getArenaWidth() - HALF_SIZE;
            boolean hitHorizontalWall = py < HALF_SIZE || py > getArenaHeight() - HALF_SIZE;
            px = clamp(px, HALF_SIZE, getArenaWidth() - HALF_SIZE);
            py = clamp(py, HALF_SIZE, getArenaHeight() - HALF_SIZE);
            if (hitVerticalWall) heading = normalize(180 - heading);
            if (hitHorizontalWall) heading = normalize(-heading);
            if (Math.hypot(px - getX(), py - getY()) <= bulletSpeed * flightTime) break;
        }

        double aim = angleTo(getX(), getY(), px, py);
        double gunTurn = normalize(aim - getGunDirection());
        setTurnGunLeft(gunTurn);
        double hitWindow = Math.toDegrees(Math.atan(HALF_SIZE / Math.max(distance, 1)));
        if (Math.abs(gunTurn) <= hitWindow && getGunHeat() == 0 && getEnergy() > power) {
            setFire(power);
        }
    }

    private void addWave(Enemy enemy, double sourceX, double sourceY, double power, int fireTurn) {
        EnemyWave wave = new EnemyWave();
        wave.x = sourceX;
        wave.y = sourceY;
        wave.speed = 20 - 3 * power;
        wave.fireTurn = fireTurn;
        wave.directAngle = angleTo(sourceX, sourceY, enemy.myXAtScan, enemy.myYAtScan);
        wave.lateralDirection = lastLateralDirection;
        wave.maxEscapeAngle = Math.toDegrees(Math.asin(Math.min(1, 8.0 / wave.speed)));
        waves.add(wave);
        if (waves.size() > MAX_WAVES) waves.remove(0);
    }

    private void updateWaves() {
        Iterator<EnemyWave> iterator = waves.iterator();
        while (iterator.hasNext()) {
            EnemyWave wave = iterator.next();
            double radius = wave.speed * (getTurnNumber() - wave.fireTurn);
            double distance = Math.hypot(getX() - wave.x, getY() - wave.y);
            if (radius > distance + HALF_SIZE + 8) {
                learn(wave, getX(), getY(), 0.12);
                iterator.remove();
            }
        }
    }

    private void learn(EnemyWave wave, double x, double y, double weight) {
        double offset = normalize(angleTo(wave.x, wave.y, x, y) - wave.directAngle);
        double factor = clamp(offset * wave.lateralDirection / wave.maxEscapeAngle, -1, 1);
        int hitBin = (int) Math.round(factor * CENTER + CENTER);
        for (int i = 0; i < BINS; i++) {
            double z = (i - hitBin) / 1.6;
            danger[i] += weight * Math.exp(-0.5 * z * z);
        }
    }

    private void move() {
        if (target == null) {
            drive(normalize(getDirection() + 35 * orbitDirection));
        } else if (getEnemyCount() <= 1) {
            moveOneOnOne();
        } else {
            moveMelee();
        }
    }

    /** In 1v1, compare forward simulations in both orbit directions against detected bullet waves. */
    private void moveOneOnOne() {
        double enemyDistance = Math.hypot(getX() - target.x, getY() - target.y);
        int horizon = 16;
        for (EnemyWave wave : waves) {
            double remaining = (Math.hypot(getX() - wave.x, getY() - wave.y)
                    - wave.speed * (getTurnNumber() - wave.fireTurn)) / wave.speed;
            if (remaining > 0) horizon = Math.min(horizon, Math.max(2, (int) Math.ceil(remaining)));
        }

        double plusRisk = orbitRisk(1, horizon, enemyDistance);
        double minusRisk = orbitRisk(-1, horizon, enemyDistance);
        if (Math.abs(plusRisk - minusRisk) > 0.001) {
            orbitDirection = plusRisk < minusRisk ? 1 : -1;
        } else if (getTurnNumber() % 35 == 0) {
            orbitDirection = -orbitDirection;
        }
        double radial = angleTo(target.x, target.y, getX(), getY());
        double desired = radial + orbitDirection * (90 + clamp((enemyDistance - 360) / 5, -35, 35));
        drive(smooth(getX(), getY(), desired, orbitDirection));
    }

    private double orbitRisk(int direction, int horizon, double enemyDistance) {
        double x = getX(), y = getY(), heading = getDirection();
        double totalRisk = direction == orbitDirection ? 0 : 0.002;
        double radial = angleTo(target.x, target.y, x, y);
        for (int tick = 1; tick <= horizon; tick++) {
            double distance = Math.hypot(x - target.x, y - target.y);
            double desired = radial + direction * (90 + clamp((distance - 360) / 5, -35, 35));
            desired = smooth(x, y, desired, direction);
            heading = normalize(heading + clamp(normalize(desired - heading), -10, 10));
            x = clamp(x + Math.cos(Math.toRadians(heading)) * 6,
                    HALF_SIZE, getArenaWidth() - HALF_SIZE);
            y = clamp(y + Math.sin(Math.toRadians(heading)) * 6,
                    HALF_SIZE, getArenaHeight() - HALF_SIZE);
            for (EnemyWave wave : waves) {
                double radius = wave.speed * (getTurnNumber() + tick - wave.fireTurn);
                double fromSource = Math.hypot(x - wave.x, y - wave.y);
                if (Math.abs(fromSource - radius) < HALF_SIZE + 8) {
                    double factor = clamp(normalize(angleTo(wave.x, wave.y, x, y)
                            - wave.directAngle) * wave.lateralDirection / wave.maxEscapeAngle, -1, 1);
                    int bin = (int) Math.round(factor * CENTER + CENTER);
                    totalRisk += 0.25 + danger[bin];
                }
            }
        }
        double edge = Math.min(Math.min(x, getArenaWidth() - x), Math.min(y, getArenaHeight() - y));
        totalRisk += 15.0 / Math.max(25, edge);
        totalRisk += Math.abs(Math.hypot(x - target.x, y - target.y) - 360) / 8000.0;
        return totalRisk;
    }

    /** In melee, select a heading whose short simulated path stays away from every known enemy. */
    private void moveMelee() {
        double bestRisk = Double.POSITIVE_INFINITY;
        double bestAngle = getDirection();
        for (int candidate = 0; candidate < 24; candidate++) {
            double travel = normalize(getDirection() + candidate * 15);
            double x = getX(), y = getY(), heading = getDirection();
            double risk = 0;
            for (int tick = 1; tick <= 12; tick++) {
                double turn = clamp(normalize(travel - heading), -10, 10);
                heading = normalize(heading + turn);
                x = clamp(x + Math.cos(Math.toRadians(heading)) * 6,
                        HALF_SIZE, getArenaWidth() - HALF_SIZE);
                y = clamp(y + Math.sin(Math.toRadians(heading)) * 6,
                        HALF_SIZE, getArenaHeight() - HALF_SIZE);
            }
            for (Enemy enemy : enemies.values()) {
                int age = getTurnNumber() - enemy.lastSeen;
                if (age > MAX_TRACK_AGE) continue;
                double predictedX = enemy.x + Math.cos(Math.toRadians(enemy.heading))
                        * enemy.speed * Math.min(12, age + 6);
                double predictedY = enemy.y + Math.sin(Math.toRadians(enemy.heading))
                        * enemy.speed * Math.min(12, age + 6);
                predictedX = clamp(predictedX, HALF_SIZE, getArenaWidth() - HALF_SIZE);
                predictedY = clamp(predictedY, HALF_SIZE, getArenaHeight() - HALF_SIZE);
                double distance = Math.hypot(x - predictedX, y - predictedY);
                double energyWeight = 0.45 + Math.min(2.0, Math.max(0, enemy.energy) / 50.0);
                risk += energyWeight * 18000.0 / (distance * distance + 9000.0);
                if (distance < 110) risk += (110 - distance) * 0.025;
            }
            double edge = Math.min(Math.min(x, getArenaWidth() - x), Math.min(y, getArenaHeight() - y));
            risk += 22.0 / Math.max(20, edge);
            risk += Math.abs(normalize(travel - getDirection())) * 0.00015;
            if (risk < bestRisk) {
                bestRisk = risk;
                bestAngle = travel;
            }
        }
        drive(smooth(getX(), getY(), bestAngle, orbitDirection));
    }

    private void drive(double travelAngle) {
        double turn = normalize(travelAngle - getDirection());
        double direction = 1;
        if (Math.abs(turn) > 90) {
            turn = normalize(turn - Math.copySign(180, turn));
            direction = -1;
        }
        setTurnLeft(turn);
        setForward(direction * 120);
    }

    private double smooth(double x, double y, double angle, int direction) {
        for (int i = 0; i <= 60; i++) {
            double candidate = angle + direction * i * 3;
            double aheadX = x + Math.cos(Math.toRadians(candidate)) * 130;
            double aheadY = y + Math.sin(Math.toRadians(candidate)) * 130;
            if (aheadX > 35 && aheadX < getArenaWidth() - 35
                    && aheadY > 35 && aheadY < getArenaHeight() - 35) return candidate;
        }
        return angle;
    }

    private void removeStaleEnemies(int now) {
        Iterator<Map.Entry<Integer, Enemy>> iterator = enemies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Enemy> entry = iterator.next();
            if (now - entry.getValue().lastSeen > MAX_TRACK_AGE) iterator.remove();
        }
    }

    @Override
    public void onHitByBullet(HitByBulletEvent event) {
        BulletState bullet = event.getBullet();
        EnemyWave best = null;
        double bestError = Double.POSITIVE_INFINITY;
        for (EnemyWave wave : waves) {
            if (Math.abs(wave.speed - bullet.getSpeed()) > 0.5) continue;
            double error = Math.abs(Math.hypot(bullet.getX() - wave.x, bullet.getY() - wave.y)
                    - wave.speed * (getTurnNumber() - wave.fireTurn));
            if (error < bestError) {
                bestError = error;
                best = wave;
            }
        }
        if (best != null) {
            learn(best, getX(), getY(), 1.0);
            waves.remove(best);
        }
        orbitDirection = -orbitDirection;
    }

    @Override
    public void onBulletHit(BulletHitBotEvent event) {
        Enemy enemy = enemies.get(event.getVictimId());
        if (enemy != null) enemy.pendingDamage += event.getDamage();
    }

    @Override
    public void onHitWall(HitWallEvent event) {
        orbitDirection = -orbitDirection;
        setForward(-80);
        setTurnLeft(90);
    }

    private double angleTo(double x1, double y1, double x2, double y2) {
        return Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
    }

    private double normalize(double angle) {
        while (angle > 180) angle -= 360;
        while (angle <= -180) angle += 360;
        return angle;
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
