import dev.robocode.tankroyale.botapi.Bot;
import dev.robocode.tankroyale.botapi.BotInfo;
import dev.robocode.tankroyale.botapi.BulletState;
import dev.robocode.tankroyale.botapi.events.BulletHitBotEvent;
import dev.robocode.tankroyale.botapi.events.HitByBulletEvent;
import dev.robocode.tankroyale.botapi.events.HitWallEvent;
import dev.robocode.tankroyale.botapi.events.ScannedBotEvent;
import dev.robocode.tankroyale.botapi.graphics.Color;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * GHCopilotBot: adaptive targeting and bullet-aware movement for Tank Royale.
 * No bot can be guaranteed to win every battle, but this uses predictive aiming,
 * learned dodge patterns, and wall-aware orbiting to improve its odds.
 */
public class GHCopilotBot extends Bot {
    private static final double HALF_SIZE = 18.0;
    private static final int BINS = 31;
    private static final int CENTER = BINS / 2;

    private double enemyX, enemyY, enemyHeading, enemySpeed, enemyEnergy = 100;
    private int enemyId = -1;
    private int lastScanTurn = -100;
    private int previousScanTurn = -100;
    private int orbitDirection = 1;
    private int lastLateralDirection = 1;
    private double previousEnemyEnergy = 100;
    private double damageDealt, energyGained;
    private double[] previousMyPosition = new double[2];
    private final double[] danger = new double[BINS];
    private final List<EnemyWave> waves = new ArrayList<>();

    private static class EnemyWave {
        double x, y, speed, directAngle, maxEscapeAngle;
        int fireTurn, lateralDirection;
    }

    public static void main(String[] args) {
        new GHCopilotBot().start();
    }

    GHCopilotBot() {
        super(BotInfo.builder()
                .setName("GHCopilotBot")
                .setVersion("2.0")
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
        previousMyPosition = new double[]{getX(), getY()};

        while (isRunning()) {
            int now = getTurnNumber();
            if (now - lastScanTurn > 3) {
                setTurnRadarLeft(Double.POSITIVE_INFINITY);
            }
            updateWaves();
            move();
            go();
        }
    }

    @Override
    public void onScannedBot(ScannedBotEvent event) {
        int now = getTurnNumber();
        int id = event.getScannedBotId();
        double distance = distanceTo(event.getX(), event.getY());

        // In melee, keep the current target unless it is lost; prefer a nearby bot.
        if (enemyId != -1 && id != enemyId && now - lastScanTurn <= 3
                && distance > distanceTo(enemyX, enemyY) * 0.8) {
            return;
        }
        if (id != enemyId) {
            enemyId = id;
            enemyEnergy = event.getEnergy();
            previousEnemyEnergy = enemyEnergy;
            previousScanTurn = now;
            waves.clear();
            damageDealt = 0;
            energyGained = 0;
        }

        double oldX = enemyX;
        double oldY = enemyY;
        double oldEnergy = previousEnemyEnergy;
        int elapsed = Math.max(1, now - lastScanTurn);
        enemyX = event.getX();
        enemyY = event.getY();
        enemyHeading = event.getDirection();
        enemySpeed = event.getSpeed();
        enemyEnergy = event.getEnergy();
        lastScanTurn = now;

        double absoluteBearing = angleTo(getX(), getY(), enemyX, enemyY);
        double lateralVelocity = getSpeed() * Math.sin(Math.toRadians(getDirection() - absoluteBearing));
        if (Math.abs(lateralVelocity) > 0.1) {
            lastLateralDirection = lateralVelocity > 0 ? 1 : -1;
        }

        // A small unexplained energy drop usually means the opponent fired.
        double unexplainedDrop = oldEnergy - damageDealt + energyGained - enemyEnergy;
        damageDealt = 0;
        energyGained = 0;
        if (id == enemyId && now - previousScanTurn <= 2
                && unexplainedDrop >= 0.1 && unexplainedDrop <= 3.01) {
            addWave(oldX, oldY, previousMyPosition[0], previousMyPosition[1], unexplainedDrop, now - 1);
        }
        previousEnemyEnergy = enemyEnergy;
        previousScanTurn = now;
        previousMyPosition[0] = getX();
        previousMyPosition[1] = getY();

        // Sweep just past the target so the radar reacquires it next tick.
        double radarTurn = normalize(angleTo(getX(), getY(), enemyX, enemyY) - getRadarDirection());
        double radarOvershoot = Math.toDegrees(2 * Math.atan(HALF_SIZE / Math.max(distance, 1))) + 2;
        setTurnRadarLeft(radarTurn + Math.copySign(radarOvershoot, radarTurn == 0 ? 1 : radarTurn));

        aimAndFire(distance, absoluteBearing);
    }

    private void aimAndFire(double distance, double absoluteBearing) {
        double power = distance < 150 ? 3.0 : distance < 350 ? 2.4 : distance < 600 ? 1.8 : 1.2;
        if (getEnergy() < 20) power = Math.min(power, 1.2);
        if (getEnergy() < 8) power = Math.min(power, 0.7);
        double killPower = enemyEnergy <= 4 ? enemyEnergy / 4 : (enemyEnergy + 2) / 6;
        power = Math.max(0.1, Math.min(power, Math.min(3.0, killPower + 0.05)));

        double bulletSpeed = 20 - 3 * power;
        double predictedX = enemyX;
        double predictedY = enemyY;
        double heading = enemyHeading;
        // Linear targeting with wall clamping; recalculate travel time each tick.
        for (int t = 1; t <= 100; t++) {
            predictedX += Math.cos(Math.toRadians(heading)) * enemySpeed;
            predictedY += Math.sin(Math.toRadians(heading)) * enemySpeed;
            predictedX = clamp(predictedX, HALF_SIZE, getArenaWidth() - HALF_SIZE);
            predictedY = clamp(predictedY, HALF_SIZE, getArenaHeight() - HALF_SIZE);
            if (Math.hypot(predictedX - getX(), predictedY - getY()) <= bulletSpeed * t) break;
        }

        double aim = angleTo(getX(), getY(), predictedX, predictedY);
        double gunTurn = normalize(aim - getGunDirection());
        setTurnGunLeft(gunTurn);
        double hitWindow = Math.toDegrees(Math.atan(HALF_SIZE / Math.max(distance, 1)));
        if (Math.abs(gunTurn) <= hitWindow && getGunHeat() == 0 && getEnergy() > power) {
            setFire(power);
        }
    }

    private void addWave(double sourceX, double sourceY, double aimedX, double aimedY, double power, int fireTurn) {
        EnemyWave wave = new EnemyWave();
        wave.x = sourceX;
        wave.y = sourceY;
        wave.speed = 20 - 3 * power;
        wave.fireTurn = fireTurn;
        wave.directAngle = angleTo(sourceX, sourceY, aimedX, aimedY);
        wave.lateralDirection = lastLateralDirection;
        wave.maxEscapeAngle = Math.toDegrees(Math.asin(Math.min(1, 8.0 / wave.speed)));
        waves.add(wave);
    }

    private void updateWaves() {
        Iterator<EnemyWave> it = waves.iterator();
        while (it.hasNext()) {
            EnemyWave wave = it.next();
            double radius = wave.speed * (getTurnNumber() - wave.fireTurn);
            double distance = Math.hypot(getX() - wave.x, getY() - wave.y);
            if (radius > distance + HALF_SIZE + 8) {
                learn(wave, getX(), getY(), 0.15);
                it.remove();
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
        if (enemyId == -1 || getTurnNumber() - lastScanTurn > 30) {
            drive(normalize(getDirection() + 35 * orbitDirection));
            return;
        }

        double targetAngle = angleTo(enemyX, enemyY, getX(), getY());
        double distance = Math.hypot(getX() - enemyX, getY() - enemyY);
        double bestRisk = Double.POSITIVE_INFINITY;
        int bestDirection = orbitDirection;

        // Compare both orbit directions over the expected time to the nearest bullet wave.
        int horizon = 18;
        for (EnemyWave wave : waves) {
            double until = (Math.hypot(getX() - wave.x, getY() - wave.y)
                    - wave.speed * (getTurnNumber() - wave.fireTurn)) / wave.speed;
            if (until > 0) horizon = Math.min(horizon, Math.max(3, (int) Math.ceil(until)));
        }
        for (int direction : new int[]{-1, 1}) {
            double x = getX(), y = getY();
            double heading = getDirection();
            for (int tick = 0; tick < horizon; tick++) {
                double orbitAngle = targetAngle + direction * (90 + clamp((distance - 360) / 5, -35, 35));
                double desired = smooth(x, y, orbitAngle, direction);
                double turn = clamp(normalize(desired - heading), -10, 10);
                heading = normalize(heading + turn);
                x = clamp(x + Math.cos(Math.toRadians(heading)) * 6,
                        HALF_SIZE, getArenaWidth() - HALF_SIZE);
                y = clamp(y + Math.sin(Math.toRadians(heading)) * 6,
                        HALF_SIZE, getArenaHeight() - HALF_SIZE);
            }
            double risk = 0;
            for (EnemyWave wave : waves) {
                double radius = wave.speed * (getTurnNumber() + horizon - wave.fireTurn);
                if (Math.hypot(x - wave.x, y - wave.y) <= radius + HALF_SIZE) {
                    double factor = clamp(normalize(angleTo(wave.x, wave.y, x, y)
                            - wave.directAngle) * wave.lateralDirection / wave.maxEscapeAngle, -1, 1);
                    int bin = (int) Math.round(factor * CENTER + CENTER);
                    risk += danger[bin];
                }
            }
            // Avoid hugging the edge and avoid predictable direction changes.
            double edge = Math.min(Math.min(x, getArenaWidth() - x), Math.min(y, getArenaHeight() - y));
            risk += 2.0 / Math.max(20, edge);
            if (direction != orbitDirection) risk += 0.002;
            if (risk < bestRisk) {
                bestRisk = risk;
                bestDirection = direction;
            }
        }

        orbitDirection = bestDirection;
        double orbitAngle = targetAngle + orbitDirection * (90 + clamp((distance - 360) / 5, -35, 35));
        drive(smooth(getX(), getY(), orbitAngle, orbitDirection));
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

    @Override
    public void onHitByBullet(HitByBulletEvent event) {
        BulletState bullet = event.getBullet();
        energyGained += 3 * bullet.getPower();
        for (Iterator<EnemyWave> it = waves.iterator(); it.hasNext();) {
            EnemyWave wave = it.next();
            if (Math.abs(wave.speed - bullet.getSpeed()) < 0.4) {
                learn(wave, getX(), getY(), 1.0);
                it.remove();
                break;
            }
        }
        orbitDirection = -orbitDirection;
    }

    @Override
    public void onBulletHit(BulletHitBotEvent event) {
        if (event.getVictimId() == enemyId) damageDealt += event.getDamage();
    }

    @Override
    public void onHitWall(HitWallEvent event) {
        orbitDirection = -orbitDirection;
        setBack(100);
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
