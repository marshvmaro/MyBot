import dev.robocode.tankroyale.botapi.Bot;
import dev.robocode.tankroyale.botapi.BotInfo;
import dev.robocode.tankroyale.botapi.events.BotDeathEvent;
import dev.robocode.tankroyale.botapi.events.BulletHitBotEvent;
import dev.robocode.tankroyale.botapi.events.ConnectionErrorEvent;
import dev.robocode.tankroyale.botapi.events.HitBotEvent;
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
 * JosiahBot 5.0
 *
 *  RADAR     : width lock in 1v1, constant sweep in melee.
 *  TARGETING : four guns, auto-selected by virtual-bullet scoring (it learns which gun hits best):
 *                0 head-on, 1 linear, 2 circular, 3 GUESS-FACTOR (statistical).
 *              The guess-factor gun now segments by THREE things: distance, sideways speed and
 *              whether the enemy is approaching or retreating. More segments = more specific stats.
 *  MOVEMENT  : orbits at a randomized distance, drives forward OR backward, smooths its path
 *              around walls, reverses when the enemy fires, backs off when losing on energy.
 *  DODGING   : the "enemy fired" detector now ignores energy the enemy lost because WE hit it
 *              (v4 mistook our own hits for enemy shots and dodged for no reason).
 *  MELEE     : pushes away from nearby bots, and favors weak and close targets.
 *
 * ANGLES: 0 = east, 90 = north, turning LEFT makes the angle bigger.
 */
public class FirstBot extends Bot {

    // ---------- constants ----------
    private static final double BOT_HALF = 18.0;       // bots are 36x36, so half-size is 18
    private static final int GUNS = 4;                 // number of virtual guns
    private static final int BINS = 31;                // guess-factor bins (odd so there is a center)
    private static final int CENTER = BINS / 2;        // index of the "enemy goes straight" bin
    private static final double MAX_BOT_SPEED = 8.0;   // fastest any bot can move
    private static final int STALE_TARGET_TURNS = 20;  // forget a target not scanned for this long
    private static final int STALE_BOT_TURNS = 100;    // forget a melee bot not seen for this long

    // ---------- target tracking ----------
    private int targetId = -1;                         // id of enemy we're shooting (-1 = none)
    private double tx, ty, tDir, tSpeed, tEnergy;      // target's last known x, y, heading, speed, energy
    private double tTurnRate = 0;                      // target's turning, in degrees per tick
    private double prevEnergy = 100;                   // target's energy at the previous scan
    private double prevDir = 0;                        // target's heading at the previous scan
    private int lastScanTurn = -100;                   // turn number of the last scan of the target
    private double ownDamageDealt = 0;                 // damage WE did to the target since last scan

    // ---------- every bot we've seen: id -> {x, y, turnSeen} ----------
    private final Map<Integer, double[]> bots = new HashMap<>();

    // ---------- movement ----------
    private int orbitDir = 1;                          // 1 or -1: which way we circle the enemy
    private int flipCooldown = 0;                      // ticks before we may reverse again
    private double prefDist = 380;                     // distance we like to keep from the enemy

    // ---------- virtual guns ----------
    private final double[] gunScore = {0.0, 0.0, 0.01, 0.0};   // circular starts slightly ahead
    private final List<VBullet> vbullets = new ArrayList<>();

    // guess-factor stats: [distance seg][sideways-speed seg][approach seg][bin]
    private final double[][][][] gf = new double[3][3][3][BINS];

    /** A "virtual bullet": a pretend shot we track only to learn from it. */
    private static class VBullet {
        double ox, oy, speed;                          // where it started, how fast it travels
        int fireTurn;                                  // turn it would have been fired
        double[] angles = new double[GUNS];            // the angle each gun would have used
        double abs;                                    // direct bearing to the enemy at fire time
        int latDir;                                    // +1/-1: enemy's sideways direction
        double mea;                                    // max escape angle (degrees)
        int ds, vs, as;                                // which stat segments this belongs to
    }

    public static void main(String[] args) {
        new FirstBot().start();
    }

    FirstBot() {
        super(BotInfo.builder()
                .setName("JosiahBot")
                .setVersion("5.0")
                .addAuthor(System.getProperty("user.name", "student"))
                .build());
    }

    @Override
    public void run() {
        // Let gun, radar and body turn independently of each other
        setAdjustGunForBodyTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForBodyTurn(true);

        setBodyColor(Color.fromRgb(0, 0, 0));
        setBulletColor(Color.fromRgb(0, 0, 0));
        setTurretColor(Color.fromRgb(0, 0, 0));
        setScanColor(Color.fromRgb(28, 101, 27));
        setRadarColor(Color.fromRgb(28, 101, 27));

        // Reset per-round state (gunScore and gf are kept on purpose so we keep learning)
        targetId = -1;
        lastScanTurn = -100;
        ownDamageDealt = 0;
        orbitDir = 1;
        flipCooldown = 0;
        bots.clear();
        vbullets.clear();

        while (isRunning()) {
            if (flipCooldown > 0) flipCooldown--;

            // Radar: sweep in melee, or whenever the lock has been lost
            if (getEnemyCount() > 1 || getTurnNumber() - lastScanTurn > 2) {
                setTurnRadarLeft(Double.POSITIVE_INFINITY);
            }
            if (getTurnNumber() - lastScanTurn > STALE_TARGET_TURNS) {
                targetId = -1;   // target is stale
            }
            // Forget melee bots we haven't seen in a long time (keeps the map small and accurate)
            bots.values().removeIf(b -> getTurnNumber() - b[2] > STALE_BOT_TURNS);

            doMovement();
            go();
        }
    }

    // =====================================================================
    //  MOVEMENT
    // =====================================================================

    private void doMovement() {
        double travel;

        if (targetId == -1) {
            // No target yet: cruise in a wide arc while the radar finds someone
            travel = getDirection() + 15 * orbitDir;
        } else {
            double dist = distanceTo(tx, ty);
            double abs = angleTo(getX(), getY(), tx, ty);

            // Preferred distance: farther in melee, and farther when we're losing on energy
            double pref = prefDist;
            if (getEnemyCount() > 1) pref += 80;
            if (getEnergy() < tEnergy - 30) pref += 100;

            // Orbit sideways; lean away if too close, lean in if too far
            double k = clamp((pref - dist) / 5.0, -45, 45);
            travel = abs + orbitDir * (90 + k);

            // Melee: push away from nearby bots (anti-gravity)
            if (getEnemyCount() > 1) {
                double vx = Math.cos(Math.toRadians(travel));
                double vy = Math.sin(Math.toRadians(travel));
                for (double[] b : bots.values()) {
                    double d = Math.hypot(getX() - b[0], getY() - b[1]);
                    if (d > 1 && d < 300) {
                        double w = (300 - d) / 300 * 1.5;   // closer bot = stronger push
                        vx += (getX() - b[0]) / d * w;
                        vy += (getY() - b[1]) / d * w;
                    }
                }
                travel = Math.toDegrees(Math.atan2(vy, vx));
            }

            // Randomly vary speed so we're harder to predict
            if (Math.random() < 0.05) setMaxSpeed(4 + Math.random() * (MAX_BOT_SPEED - 4));
        }

        travel = wallSmooth(travel);
        driveToward(travel);
    }

    /** Turn the shortest way and drive forward or backward toward the travel direction. */
    private void driveToward(double travel) {
        double delta = norm(travel - getDirection());
        int dir = 1;
        if (Math.abs(delta) > 90) {      // target direction is behind us: reverse instead of U-turning
            delta = norm(delta - 180);
            dir = -1;
        }
        setTurnLeft(delta);
        setForward(150 * dir);
    }

    /** Rotate the travel direction (smallest change first) until the path ahead stays inside the arena. */
    private double wallSmooth(double angle) {
        for (int i = 0; i <= 36; i++) {
            for (int s = 1; s >= -1; s -= 2) {
                double a = angle + s * i * 5;
                if (insideField(a)) return a;
                if (i == 0) break;       // at i = 0 both signs are the same angle, so test once
            }
        }
        return angle;
    }

    private boolean insideField(double angle) {
        double look = 130;               // how far ahead we check
        double margin = 40;              // how close to a wall is too close
        double x = getX() + Math.cos(Math.toRadians(angle)) * look;
        double y = getY() + Math.sin(Math.toRadians(angle)) * look;
        return x > margin && x < getArenaWidth() - margin
                && y > margin && y < getArenaHeight() - margin;
    }

    private void flipDirection(boolean force) {
        if (force || flipCooldown == 0) {
            orbitDir = -orbitDir;
            flipCooldown = 10;
            prefDist = 320 + Math.random() * 130;   // new preferred distance each time
        }
    }

    // =====================================================================
    //  SCANNING, TARGETING, FIRING
    // =====================================================================

    @Override
    public void onScannedBot(ScannedBotEvent e) {
        int id = e.getScannedBotId();
        double dist = distanceTo(e.getX(), e.getY());

        bots.put(id, new double[]{e.getX(), e.getY(), getTurnNumber()});

        double currentTargetDist = (targetId == -1) ? Double.MAX_VALUE : distanceTo(tx, ty);

        // Pick the target: stick with it, unless there's none, someone is much closer,
        // or someone is much weaker and not far away
        boolean switchTarget = (targetId == -1);
        if (!switchTarget && id != targetId) {
            if (dist < currentTargetDist * 0.7) switchTarget = true;
            else if (e.getEnergy() < tEnergy * 0.5 && dist < currentTargetDist * 1.3) switchTarget = true;
        }
        if (switchTarget) {
            targetId = id;
            tTurnRate = 0;
            prevEnergy = e.getEnergy();
            prevDir = e.getDirection();
            tEnergy = e.getEnergy();
            ownDamageDealt = 0;
            lastScanTurn = getTurnNumber() - 1;
            vbullets.clear();   // old virtual bullets belonged to the previous target
        }
        if (id != targetId) return;

        // ---- Track enemy turn rate and energy ----
        int dt = Math.max(1, getTurnNumber() - lastScanTurn);
        tTurnRate = norm(e.getDirection() - prevDir) / dt;

        // Energy the enemy lost that was NOT caused by our own bullets = it probably fired.
        double energyDrop = prevEnergy - e.getEnergy() - ownDamageDealt;
        ownDamageDealt = 0;
        if (energyDrop >= 0.1 && energyDrop <= 3.0 && Math.random() < 0.8) {
            flipDirection(false);   // enemy just fired: change direction to dodge
        }
        prevEnergy = e.getEnergy();
        prevDir = e.getDirection();

        tx = e.getX();
        ty = e.getY();
        tDir = e.getDirection();
        tSpeed = e.getSpeed();
        tEnergy = e.getEnergy();
        lastScanTurn = getTurnNumber();

        // ---- Width lock (1v1 only; in melee the radar keeps sweeping) ----
        if (getEnemyCount() == 1) {
            double neededTurn = radarBearingTo(tx, ty);
            double angularWidth = Math.toDegrees(2 * Math.atan(BOT_HALF / dist));
            double overshoot = Math.copySign(angularWidth / 2, neededTurn);
            setTurnRadarLeft(neededTurn + overshoot);
        }

        // ---- Learn from virtual bullets that have now reached the enemy ----
        resolveVirtualBullets();

        // ---- Fire power ----
        double power;
        if (dist < 150) power = 3;
        else if (dist < 350) power = 2.5;
        else if (dist < 600) power = 1.8;
        else power = 1.2;
        if (getEnemyCount() > 1 && dist > 500) power = Math.min(power, 1.5);
        if (getEnergy() < 20) power = Math.min(power, 1.5);
        if (getEnergy() < 8) power = Math.min(power, 0.8);
        // don't waste more energy than needed to finish off a weak enemy
        power = Math.min(power, Math.max(0.1, tEnergy / 4 + 0.1));
        power = Math.max(0.1, Math.min(3, power));

        double bulletSpeed = calcBulletSpeed(power);

        // ---- Situation (for the guess-factor gun) ----
        double abs = angleTo(getX(), getY(), tx, ty);
        double lv = tSpeed * Math.sin(Math.toRadians(tDir - abs));   // enemy's sideways speed
        double av = tSpeed * Math.cos(Math.toRadians(tDir - abs));   // + = moving away, - = approaching
        int latDir = lv >= 0 ? 1 : -1;
        double alv = Math.abs(lv);
        int ds = dist < 250 ? 0 : (dist < 450 ? 1 : 2);
        int vs = alv < 2 ? 0 : (alv < 5 ? 1 : 2);
        int as = av < -2 ? 0 : (av <= 2 ? 1 : 2);
        double mea = Math.toDegrees(Math.asin(MAX_BOT_SPEED / bulletSpeed));   // max escape angle

        // ---- Aim angle for each gun (absolute degrees) ----
        double[] aim = new double[GUNS];
        aim[0] = abs;                                                        // head-on
        double[] lin = predict(0, bulletSpeed);
        aim[1] = angleTo(getX(), getY(), lin[0], lin[1]);                    // linear
        double[] cir = predict(tTurnRate, bulletSpeed);
        aim[2] = angleTo(getX(), getY(), cir[0], cir[1]);                    // circular

        double[] stats = gf[ds][vs][as];
        int bestBin = CENTER;
        for (int b = 0; b < BINS; b++) {
            if (stats[b] > stats[bestBin] + 1e-9) bestBin = b;
        }
        double gfAim = (bestBin - CENTER) / (double) CENTER;
        aim[3] = abs + latDir * gfAim * mea;                                 // guess-factor

        // Record a virtual bullet for every gun (scored later)
        VBullet vb = new VBullet();
        vb.ox = getX();
        vb.oy = getY();
        vb.speed = bulletSpeed;
        vb.fireTurn = getTurnNumber() + 1;
        vb.abs = abs;
        vb.latDir = latDir;
        vb.mea = mea;
        vb.ds = ds;
        vb.vs = vs;
        vb.as = as;
        System.arraycopy(aim, 0, vb.angles, 0, GUNS);
        vbullets.add(vb);
        if (vbullets.size() > 200) vbullets.remove(0);

        // ---- Use whichever gun has been hitting best (ties go to circular) ----
        int best = 2;
        for (int g = 0; g < GUNS; g++) {
            if (gunScore[g] > gunScore[best]) best = g;
        }

        // ---- Aim and fire ----
        double gunTurn = norm(aim[best] - getGunDirection());
        setTurnGunLeft(gunTurn);
        double tolerance = Math.toDegrees(Math.atan(BOT_HALF / Math.max(dist, 1)));
        if (Math.abs(gunTurn) <= tolerance && getGunHeat() == 0 && getEnergy() > power) {
            setFire(power);
        }
    }

    /** Predict where the enemy will be when a bullet of the given speed arrives. */
    private double[] predict(double turnRate, double bulletSpeed) {
        double px = tx, py = ty, heading = tDir;
        for (int t = 1; t <= 100; t++) {
            heading += turnRate;
            double r = Math.toRadians(heading);
            px += Math.cos(r) * tSpeed;
            py += Math.sin(r) * tSpeed;
            px = clamp(px, BOT_HALF, getArenaWidth() - BOT_HALF);
            py = clamp(py, BOT_HALF, getArenaHeight() - BOT_HALF);
            if (distanceTo(px, py) <= bulletSpeed * t) break;
        }
        return new double[]{px, py};
    }

    /**
     * A virtual bullet "arrives" once it has traveled as far as the enemy is from its origin.
     * Then we (1) score each gun by whether it would have hit and
     *             (2) record where the enemy really was, in guess-factor terms.
     */
    private void resolveVirtualBullets() {
        Iterator<VBullet> it = vbullets.iterator();
        while (it.hasNext()) {
            VBullet vb = it.next();
            int age = getTurnNumber() - vb.fireTurn;
            if (age > 120) {
                it.remove();
                continue;
            }
            double d = Math.hypot(tx - vb.ox, ty - vb.oy);
            if (vb.speed * age >= d) {
                double actual = angleTo(vb.ox, vb.oy, tx, ty);
                double tol = Math.toDegrees(Math.atan(BOT_HALF / Math.max(d, 1)));

                // score the guns (old scores fade by 3% so recent results matter most)
                for (int g = 0; g < GUNS; g++) {
                    boolean hit = Math.abs(norm(actual - vb.angles[g])) <= tol;
                    gunScore[g] = gunScore[g] * 0.97 + (hit ? 1 : 0);
                }

                // update guess-factor stats (smoothed around the observed bin)
                double offset = norm(actual - vb.abs);
                double gfValue = clamp(offset * vb.latDir / vb.mea, -1, 1);
                int idx = (int) Math.round(gfValue * CENTER + CENTER);
                double[] st = gf[vb.ds][vb.vs][vb.as];
                for (int b = 0; b < BINS; b++) {
                    st[b] = st[b] * 0.995 + 1.0 / (1 + (b - idx) * (b - idx));
                }

                it.remove();
            }
        }
    }

    // =====================================================================
    //  OTHER EVENTS
    // =====================================================================

    @Override
    public void onBulletHit(BulletHitBotEvent e) {
        // Remember the damage we caused so it isn't mistaken for the enemy firing
        if (e.getVictimId() == targetId) {
            ownDamageDealt += e.getDamage();
        }
    }

    @Override
    public void onBotDeath(BotDeathEvent e) {
        bots.remove(e.getVictimId());
        if (e.getVictimId() == targetId) {
            targetId = -1;
            vbullets.clear();
        }
    }

    @Override
    public void onHitBot(HitBotEvent e) {
        flipDirection(true);   // collision: back off instead of trading ram damage
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        flipDirection(true);
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        flipDirection(false);
    }

    @Override
    public void onConnectionError(ConnectionErrorEvent e) {
        System.out.println("Could not connect to Robocode Tank Royale.");
        System.out.println("Open the Tank Royale app and press Ctrl+B (Battle -> Start Battle) FIRST,");
        System.out.println("then click Run again.");
        System.exit(1);
    }

    // =====================================================================
    //  HELPERS
    // =====================================================================

    /** Absolute angle (degrees, 0 = east) from (x1, y1) to (x2, y2). */
    private static double angleTo(double x1, double y1, double x2, double y2) {
        return Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
    }

    /** Normalizes an angle to the range -180..180. */
    private static double norm(double angle) {
        while (angle > 180) angle -= 360;
        while (angle <= -180) angle += 360;
        return angle;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
