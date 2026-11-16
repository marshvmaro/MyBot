import dev.robocode.tankroyale.botapi.Bot;
import dev.robocode.tankroyale.botapi.BotInfo;
import dev.robocode.tankroyale.botapi.BulletState;
import dev.robocode.tankroyale.botapi.events.BotDeathEvent;
import dev.robocode.tankroyale.botapi.events.BulletHitBotEvent;
import dev.robocode.tankroyale.botapi.events.BulletHitBulletEvent;
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
 *  RADAR     : width lock in 1v1. In melee it locks the target and does a full sweep every so often,
 *              so the target is tracked closely while everyone else is still seen.
 *  TARGETING : four guns, auto-selected by virtual-bullet scoring:
 *                0 head-on, 1 linear, 2 circular, 3 GUESS-FACTOR (statistical).
 *              The guess-factor gun now uses FOUR stat buffers (different situations and memory lengths)
 *              that vote together, and it never aims outside the arena.
 *  MOVEMENT  : 1v1  -> WAVE SURFING. It detects enemy shots (energy drops), tracks the "wave" each bullet
 *                      makes, remembers where it got hit, and drives to the safer side of every incoming wave.
 *              melee -> MINIMUM-RISK movement. It samples points around itself and drives to the safest one
 *                      (far from enemies, off the walls and out of corners).
 *  FIRE      : power depends on distance and energy, and never wastes more than a kill shot needs.
 *
 * ANGLES: 0 = east, 90 = north, turning LEFT makes the angle bigger.
 * ORBIT : orbitDir +1 = circle the enemy counter-clockwise (our angle around it grows), -1 = clockwise.
 */
public class OPBot extends Bot {

    private static final double BOT_HALF = 18.0;     // bots are 36x36
    private static final int GUNS = 4;
    private static final int BINS = 31;              // guess-factor bins
    private static final int CENTER = BINS / 2;

    // ---- tuning knobs ----
    private static final double PASS_WEIGHT = 0.15;  // surf stats: how much a wave that MISSED us counts
    private static final int WAVE_OFFSET = -1;       // timing calibration: measured against real hits in Tank Royale 1.4.0

    // ---- target tracking ----
    private int targetId = -1;
    private double tx, ty, tDir, tSpeed, tEnergy;
    private double tTurnRate = 0;
    private double prevDir = 0;
    private int lastScanTurn = -100;
    private int lastLatDir = 1;                      // enemy's last non-zero sideways direction
    private int lastChangeTurn = 0;                  // when the enemy last changed direction / speed a lot

    // ---- enemy shot detection (1v1 surfing) ----
    private int scanTurn = -100;                     // turn of the newest scan of the target
    private int prevScanTurn = -100;
    private double prevEnergy = 100;
    private double pTx, pTy;                         // target position at the previous scan
    private double pMeX, pMeY;                       // our position at the previous scan
    private int myLatDirPrev = 1, myLatDir = 1;      // our sideways direction relative to the enemy
    private double dmgDealt = 0, gainFromHit = 0;    // energy changes explained by bullet hits
    private int lastCollisionTurn = -100;

    // ---- every bot we've seen: id -> {x, y, turnSeen, energy} ----
    private final Map<Integer, double[]> bots = new HashMap<>();

    // ---- movement ----
    private int orbitDir = 1;
    private int flipCooldown = 0;
    private double prefDist = 380;
    private double destX, destY;                     // melee destination
    private int destTurn = -100;

    // ---- radar (melee) ----
    private int sweepUntil = -1;
    private int lastSweepEnd = 0;

    // ---- enemy waves + surf stats ----
    private static class EWave {
        double ox, oy, speed, directAngle, mea;
        int fireTurn, latDir;
    }
    private final List<EWave> eWaves = new ArrayList<>();
    private final double[] surf = new double[BINS];

    // ---- virtual guns ----
    private final double[] gunScore = {0.0, 0.0, 0.01, 0.0};
    private final List<VBullet> vbullets = new ArrayList<>();

    // guess-factor buffers (see segments() below) and how fast each one forgets
    private final double[][][] gf = {
            new double[16][BINS],   // 0: flight time x sideways speed
            new double[36][BINS],   // 1: sideways speed x wall room x acceleration
            new double[36][BINS],   // 2: flight time x advancing speed x time since change
            new double[1][BINS]     // 3: everything together, forgets fast
    };
    private static final double[] GF_DECAY = {0.995, 0.99, 0.993, 0.97};
    private static final double[] GF_WEIGHT = {1.0, 1.0, 1.0, 0.5};

    private static class VBullet {
        double ox, oy, speed;
        int fireTurn;
        double[] angles = new double[GUNS];
        double abs;
        int latDir;
        double mea;
        int[] seg = new int[4];
    }

    public static void main(String[] args) {
        new OPBot().start();
    }

    OPBot() {
        super(BotInfo.builder()
                .setName("OPBot")
                .setVersion("1.0")
                .addAuthor("me")
                .build());
    }

    @Override
    public void run() {
        setAdjustGunForBodyTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForBodyTurn(true);

        setBodyColor(Color.fromRgb(0, 0, 0));
        setBulletColor(Color.fromRgb(0, 0, 0));
        setTurretColor(Color.fromRgb(0, 0, 0));
        setScanColor(Color.fromRgb(28, 101, 27));
        setRadarColor(Color.fromRgb(28, 101, 27));

        // Reset per-round state (gunScore, gf and surf are kept on purpose so we keep learning)
        targetId = -1;
        lastScanTurn = -100;
        scanTurn = -100;
        prevScanTurn = -100;
        orbitDir = Math.random() < 0.5 ? 1 : -1;
        flipCooldown = 0;
        dmgDealt = 0;
        gainFromHit = 0;
        lastCollisionTurn = -100;
        sweepUntil = -1;
        lastSweepEnd = 0;
        destTurn = -100;
        bots.clear();
        vbullets.clear();
        eWaves.clear();
        setMaxSpeed(8);

        while (isRunning()) {
            if (flipCooldown > 0) flipCooldown--;

            doRadar();
            if (getTurnNumber() - lastScanTurn > 20) {
                targetId = -1;   // target is stale
            }

            processEnemyShots();
            updateWaves();
            doMovement();
            go();
        }
    }

    // =====================================================================
    //  RADAR
    // =====================================================================

    private void doRadar() {
        int now = getTurnNumber();
        boolean lost = now - lastScanTurn > 2;
        if (getEnemyCount() > 1) {
            if (now < sweepUntil || targetId == -1 || lost) {
                setTurnRadarLeft(Double.POSITIVE_INFINITY);
            } else if (now - lastSweepEnd > 25) {
                sweepUntil = now + 9;          // one full turn takes 8 ticks
                lastSweepEnd = now + 9;
                setTurnRadarLeft(Double.POSITIVE_INFINITY);
            }
        } else if (lost) {
            setTurnRadarLeft(Double.POSITIVE_INFINITY);
        }
    }

    // =====================================================================
    //  ENEMY SHOTS -> WAVES
    // =====================================================================

    /** Runs once per tick after the events: did the target just fire? If so, start a wave. */
    private void processEnemyShots() {
        int now = getTurnNumber();
        if (targetId == -1 || scanTurn != now) return;

        double expected = prevEnergy - dmgDealt + gainFromHit;
        double drop = expected - tEnergy;
        dmgDealt = 0;
        gainFromHit = 0;

        boolean collided = now - lastCollisionTurn <= 1;
        if (drop >= 0.09 && drop <= 3.01 && !collided && scanTurn - prevScanTurn <= 2) {
            if (getEnemyCount() == 1) {
                spawnWave(drop);
            } else if (Math.random() < 0.8) {
                flipDirection(false);
            }
        }

        prevEnergy = tEnergy;
        prevScanTurn = scanTurn;
        pTx = tx;
        pTy = ty;
        pMeX = getX();
        pMeY = getY();
        myLatDirPrev = myLatDir;
        double lat = getSpeed() * Math.sin(Math.toRadians(getDirection() - angleTo(tx, ty, getX(), getY())));
        if (Math.abs(lat) > 0.1) myLatDir = lat > 0 ? 1 : -1;
    }

    private void spawnWave(double power) {
        EWave w = new EWave();
        w.ox = tx;
        w.oy = ty;
        w.speed = 20 - 3 * power;
        w.fireTurn = scanTurn + WAVE_OFFSET;
        w.directAngle = angleTo(w.ox, w.oy, pMeX, pMeY);
        w.latDir = myLatDirPrev;
        w.mea = Math.toDegrees(Math.asin(8.0 / w.speed));
        eWaves.add(w);
    }

    private double waveRadius(EWave w, int turn) {
        return w.speed * (turn - w.fireTurn);
    }

    /** Drop waves that already passed us (learning a little from the ones that missed). */
    private void updateWaves() {
        int now = getTurnNumber();
        Iterator<EWave> it = eWaves.iterator();
        while (it.hasNext()) {
            EWave w = it.next();
            double d = Math.hypot(getX() - w.ox, getY() - w.oy);
            if (waveRadius(w, now) > d + BOT_HALF + 4) {
                if (PASS_WEIGHT > 0) recordSurf(w, getX(), getY(), PASS_WEIGHT);
                it.remove();
            }
        }
    }

    private double gfOf(EWave w, double px, double py) {
        double offset = norm(angleTo(w.ox, w.oy, px, py) - w.directAngle);
        return clamp(offset * w.latDir / w.mea, -1, 1);
    }

    private void recordSurf(EWave w, double px, double py, double weight) {
        int idx = (int) Math.round(gfOf(w, px, py) * CENTER + CENTER);
        for (int b = 0; b < BINS; b++) {
            double z = (b - idx) / 1.6;
            surf[b] += weight * Math.exp(-0.5 * z * z);
        }
    }

    // =====================================================================
    //  MOVEMENT
    // =====================================================================

    private void doMovement() {
        if (targetId == -1) {
            // No target yet: cruise in a wide arc while the radar finds someone
            double travel = smooth(getX(), getY(), getDirection() + 15 * orbitDir, orbitDir);
            driveToward(travel, 150);
        } else if (getEnemyCount() == 1) {
            surfMove();
        } else {
            meleeMove();
        }
    }

    // ---------- 1v1: wave surfing ----------

    private void surfMove() {
        double pref = prefDist;
        if (getEnergy() < tEnergy - 30) pref += 100;

        int dir = orbitDir;
        List<EWave> ws = upcomingWaves(2);
        if (!ws.isEmpty()) {
            double dPlus = predictDanger(ws, 1, pref);
            double dMinus = predictDanger(ws, -1, pref);
            if (dPlus < dMinus - 1e-9) dir = 1;
            else if (dMinus < dPlus - 1e-9) dir = -1;
            orbitDir = dir;
        } else if (flipCooldown == 0 && Math.random() < 0.03) {
            flipDirection(false);   // nothing incoming: still change direction now and then
            dir = orbitDir;
        }

        double travel = orbitAngle(getX(), getY(), tx, ty, dir, pref);
        travel = smooth(getX(), getY(), travel, dir);
        driveToward(travel, 120);
    }

    /** The (up to n) waves that are still on their way, soonest first. */
    private List<EWave> upcomingWaves(int n) {
        int now = getTurnNumber();
        List<EWave> out = new ArrayList<>(eWaves);
        out.sort((a, b) -> Double.compare(timeLeft(a, now), timeLeft(b, now)));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    private double timeLeft(EWave w, int now) {
        double d = Math.hypot(getX() - w.ox, getY() - w.oy);
        return (d - waveRadius(w, now)) / w.speed;
    }

    /** Direction to drive to circle (ex, ey) in direction dir, nudged toward/away from the preferred distance. */
    private double orbitAngle(double x, double y, double ex, double ey, int dir, double pref) {
        double theta = angleTo(ex, ey, x, y);      // from the enemy to us
        double d = Math.hypot(x - ex, y - ey);
        double k = clamp((d - pref) / 5.0, -45, 45);   // too far: lean toward the enemy
        return theta + dir * (90 + k);
    }

    /**
     * Pretend to keep orbiting in direction dir (using the real turn / acceleration limits) and see where we
     * would be when each wave reaches us. The danger is how often we have been hit at that spot before.
     */
    private double predictDanger(List<EWave> ws, int dir, double pref) {
        int now = getTurnNumber();
        EWave w0 = ws.get(0);
        double px = getX(), py = getY(), ph = getDirection(), pv = getSpeed();
        boolean[] done = new boolean[ws.size()];
        int remaining = ws.size();
        double total = 0;
        double lo = BOT_HALF, hiX = getArenaWidth() - BOT_HALF, hiY = getArenaHeight() - BOT_HALF;

        for (int t = 1; t <= 160 && remaining > 0; t++) {
            double want = smooth(px, py, orbitAngle(px, py, w0.ox, w0.oy, dir, pref), dir);
            double turn = norm(want - ph);
            int md = 1;
            if (Math.abs(turn) > 90) {
                turn = norm(turn - 180);
                md = -1;
            }
            double maxT = 10 - 0.75 * Math.abs(pv);
            ph = norm(ph + clamp(turn, -maxT, maxT));
            pv += (pv * md < 0) ? 2 * md : md;
            pv = clamp(pv, -8, 8);
            px = clamp(px + Math.cos(Math.toRadians(ph)) * pv, lo, hiX);
            py = clamp(py + Math.sin(Math.toRadians(ph)) * pv, lo, hiY);

            for (int i = 0; i < ws.size(); i++) {
                if (done[i]) continue;
                EWave w = ws.get(i);
                if (Math.hypot(px - w.ox, py - w.oy) <= waveRadius(w, now + t)) {
                    done[i] = true;
                    remaining--;
                    int idx = (int) Math.round(gfOf(w, px, py) * CENTER + CENTER);
                    total += surf[idx] * (i == 0 ? 1.0 : 0.5);
                }
            }
        }
        return total;
    }

    // ---------- melee: minimum risk ----------

    private void meleeMove() {
        double x = getX(), y = getY();
        int now = getTurnNumber();
        if (now - destTurn > 14 || Math.hypot(destX - x, destY - y) < 25) {
            pickDestination();
        }
        double d = Math.hypot(destX - x, destY - y);
        driveToward(angleTo(x, y, destX, destY), Math.max(20, Math.min(d, 150)));
    }

    private void pickDestination() {
        double x = getX(), y = getY();
        double best = Double.MAX_VALUE;
        double bx = x, by = y;
        double margin = 35;
        for (int i = 0; i < 36; i++) {
            double ang = Math.toRadians(i * 10 + Math.random() * 10);
            double r = 70 + Math.random() * 110;
            double px = x + Math.cos(ang) * r;
            double py = y + Math.sin(ang) * r;
            if (px < margin || px > getArenaWidth() - margin || py < margin || py > getArenaHeight() - margin) continue;
            double risk = riskAt(px, py, x, y);
            if (risk < best) {
                best = risk;
                bx = px;
                by = py;
            }
        }
        destX = bx;
        destY = by;
        destTurn = getTurnNumber();
    }

    private double riskAt(double px, double py, double x, double y) {
        int now = getTurnNumber();
        double risk = 0;
        for (double[] b : bots.values()) {
            if (now - b[2] > 40) continue;                      // old info
            double d2 = Math.max(400, (px - b[0]) * (px - b[0]) + (py - b[1]) * (py - b[1]));
            double a1 = angleTo(b[0], b[1], x, y);
            double a2 = angleTo(b[0], b[1], px, py);
            double perp = 1 + Math.abs(Math.cos(Math.toRadians(a1 - a2)));  // moving toward them is worse than sideways
            double weight = 1 + b[3] / 50.0;                    // stronger enemies are scarier
            risk += weight * perp * 90000 / d2;
        }
        double edge = Math.min(Math.min(px, getArenaWidth() - px), Math.min(py, getArenaHeight() - py));
        if (edge < 120) risk += (120 - edge) / 120 * 1.5;
        double cx = Math.min(px, getArenaWidth() - px), cy = Math.min(py, getArenaHeight() - py);
        if (cx < 150 && cy < 150) risk += 2;                    // corners are traps
        return risk + Math.random() * 0.05;
    }

    // ---------- shared driving helpers ----------

    /** Turn the shortest way and drive forward or backward toward the travel direction. */
    private void driveToward(double travel, double distance) {
        double delta = norm(travel - getDirection());
        int dir = 1;
        if (Math.abs(delta) > 90) {
            delta = norm(delta - 180);
            dir = -1;
        }
        setTurnLeft(delta);
        setForward(distance * dir);
    }

    /**
     * Rotate the travel direction (toward the enemy side, step by step) until the path ahead stays inside
     * the arena. 'dir' is the orbit direction, so +dir turns toward the enemy.
     */
    private double smooth(double x, double y, double angle, int dir) {
        for (int i = 0; i <= 60; i++) {
            double a = angle + dir * i * 3;
            if (insideField(x, y, a)) return a;
        }
        return angle;
    }

    private boolean insideField(double fromX, double fromY, double angle) {
        double look = 140;
        double margin = 28;
        double x = fromX + Math.cos(Math.toRadians(angle)) * look;
        double y = fromY + Math.sin(Math.toRadians(angle)) * look;
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
        int now = getTurnNumber();
        double dist = distanceTo(e.getX(), e.getY());

        bots.put(id, new double[]{e.getX(), e.getY(), now, e.getEnergy()});

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
            prevDir = e.getDirection();
            tx = pTx = e.getX();
            ty = pTy = e.getY();
            tDir = e.getDirection();
            tSpeed = e.getSpeed();
            tEnergy = prevEnergy = e.getEnergy();
            lastScanTurn = now - 1;
            prevScanTurn = now - 1;
            lastChangeTurn = now;
            dmgDealt = 0;
            gainFromHit = 0;
            vbullets.clear();   // old virtual bullets belonged to the previous target
            eWaves.clear();
        }
        if (id != targetId) return;

        // ---- Track enemy state ----
        int dt = Math.max(1, now - lastScanTurn);
        tTurnRate = norm(e.getDirection() - prevDir) / dt;
        prevDir = e.getDirection();
        double prevSpeed = tSpeed;

        tx = e.getX();
        ty = e.getY();
        tDir = e.getDirection();
        tSpeed = e.getSpeed();
        tEnergy = e.getEnergy();
        lastScanTurn = now;
        scanTurn = now;

        // ---- Radar lock (1v1 always; in melee except while doing a full sweep) ----
        if (getEnemyCount() == 1 || now >= sweepUntil) {
            double neededTurn = radarBearingTo(tx, ty);
            double angularWidth = Math.toDegrees(2 * Math.atan(BOT_HALF / Math.max(dist, 1)));
            double overshoot = Math.copySign(angularWidth * 0.75 + 2, neededTurn);
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
        // never use more power than a kill shot needs (damage = 4p, or 6p - 2 above power 1)
        double killPower = tEnergy <= 4 ? tEnergy / 4 : (tEnergy + 2) / 6;
        power = Math.min(power, Math.max(0.1, killPower + 0.05));
        power = Math.max(0.1, Math.min(3, power));

        double bulletSpeed = calcBulletSpeed(power);

        // ---- Situation (for the guess-factor gun) ----
        double abs = angleTo(getX(), getY(), tx, ty);
        double lv = tSpeed * Math.sin(Math.toRadians(tDir - abs));   // enemy's sideways speed
        if (Math.abs(lv) > 0.5) {
            int s = lv > 0 ? 1 : -1;
            if (s != lastLatDir) lastChangeTurn = now;
            lastLatDir = s;
        }
        if (Math.abs(tSpeed - prevSpeed) > 1.5) lastChangeTurn = now;
        int latDir = lastLatDir;
        int[] seg = segments(dist, bulletSpeed, lv, prevSpeed, now);
        double mea = Math.toDegrees(Math.asin(8.0 / bulletSpeed));   // max escape angle

        // ---- Aim angle for each gun (absolute degrees) ----
        double[] aim = new double[GUNS];
        aim[0] = abs;                                                        // head-on
        double[] lin = predict(0, bulletSpeed);
        aim[1] = angleTo(getX(), getY(), lin[0], lin[1]);                    // linear
        double[] cir = predict(tTurnRate, bulletSpeed);
        aim[2] = angleTo(getX(), getY(), cir[0], cir[1]);                    // circular

        double[] comb = combinedStats(seg);
        int bestBin = CENTER;
        for (int b = 0; b < BINS; b++) {
            if (comb[b] > comb[bestBin] + 1e-9) bestBin = b;
        }
        double gfAim = (bestBin - CENTER) / (double) CENTER;
        aim[3] = abs + latDir * gfAim * mea;                                 // guess-factor
        for (int g = 0; g < GUNS; g++) aim[g] = clampAim(aim[g], dist);      // never aim outside the arena

        // Record a virtual bullet for every gun (scored later)
        VBullet vb = new VBullet();
        vb.ox = getX();
        vb.oy = getY();
        vb.speed = bulletSpeed;
        vb.fireTurn = now + 1;
        vb.abs = abs;
        vb.latDir = latDir;
        vb.mea = mea;
        System.arraycopy(seg, 0, vb.seg, 0, 4);
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

    /** Which segment of each guess-factor buffer describes the current situation. */
    private int[] segments(double dist, double bulletSpeed, double lv, double prevSpeed, int now) {
        double alv = Math.abs(lv);
        double flight = dist / bulletSpeed;
        int ft = flight < 10 ? 0 : (flight < 20 ? 1 : (flight < 30 ? 2 : 3));
        int lvb = alv < 1 ? 0 : (alv < 3 ? 1 : (alv < 5.5 ? 2 : 3));

        double abs = angleTo(getX(), getY(), tx, ty);
        double adv = -tSpeed * Math.cos(Math.toRadians(tDir - abs));   // + means coming toward us
        int advb = adv < -2 ? 0 : (adv < 2 ? 1 : 2);

        double moveAngle = tSpeed >= 0 ? tDir : tDir + 180;
        double room = rayToWall(tx, ty, moveAngle) / (8 * flight + 1);
        int wallb = room < 0.6 ? 0 : (room < 1.2 ? 1 : 2);

        double accel = Math.abs(tSpeed) - Math.abs(prevSpeed);
        int accb = accel < -0.5 ? 0 : (accel > 0.5 ? 2 : 1);

        int since = now - lastChangeTurn;
        int sinceb = since < 12 ? 0 : (since < 35 ? 1 : 2);

        return new int[]{
                ft * 4 + lvb,
                (lvb * 3 + wallb) * 3 + accb,
                (ft * 3 + advb) * 3 + sinceb,
                0
        };
    }

    /** Every buffer votes; a segment with little data has little say. */
    private double[] combinedStats(int[] seg) {
        double[] out = new double[BINS];
        for (int k = 0; k < gf.length; k++) {
            double[] st = gf[k][seg[k]];
            double total = 1;
            for (int b = 0; b < BINS; b++) total += st[b];
            for (int b = 0; b < BINS; b++) out[b] += GF_WEIGHT[k] * st[b] / total;
        }
        return out;
    }

    /** Distance from (x, y) to the arena edge (inset by half a bot) along the given direction. */
    private double rayToWall(double x, double y, double angle) {
        double dx = Math.cos(Math.toRadians(angle)), dy = Math.sin(Math.toRadians(angle));
        double t = 1e9;
        if (dx > 1e-9) t = Math.min(t, (getArenaWidth() - BOT_HALF - x) / dx);
        else if (dx < -1e-9) t = Math.min(t, (BOT_HALF - x) / dx);
        if (dy > 1e-9) t = Math.min(t, (getArenaHeight() - BOT_HALF - y) / dy);
        else if (dy < -1e-9) t = Math.min(t, (BOT_HALF - y) / dy);
        return Math.max(0, t);
    }

    /** If an aim angle points outside the arena, pull the aim point back inside it. */
    private double clampAim(double angle, double dist) {
        double px = clamp(getX() + Math.cos(Math.toRadians(angle)) * dist, BOT_HALF, getArenaWidth() - BOT_HALF);
        double py = clamp(getY() + Math.sin(Math.toRadians(angle)) * dist, BOT_HALF, getArenaHeight() - BOT_HALF);
        return angleTo(getX(), getY(), px, py);
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

                // score the guns
                for (int g = 0; g < GUNS; g++) {
                    boolean hit = Math.abs(norm(actual - vb.angles[g])) <= tol;
                    gunScore[g] = gunScore[g] * 0.97 + (hit ? 1 : 0);
                }

                // update every guess-factor buffer (smoothed around the observed bin)
                double offset = norm(actual - vb.abs);
                double g = clamp(offset * vb.latDir / vb.mea, -1, 1);
                int idx = (int) Math.round(g * CENTER + CENTER);
                for (int k = 0; k < gf.length; k++) {
                    double[] st = gf[k][vb.seg[k]];
                    for (int b = 0; b < BINS; b++) {
                        double z = (b - idx) / 1.5;
                        st[b] = st[b] * GF_DECAY[k] + Math.exp(-0.5 * z * z);
                    }
                }

                it.remove();
            }
        }
    }

    // =====================================================================
    //  OTHER EVENTS
    // =====================================================================

    @Override
    public void onBotDeath(BotDeathEvent e) {
        bots.remove(e.getVictimId());
        if (e.getVictimId() == targetId) {
            targetId = -1;
            vbullets.clear();
            eWaves.clear();
        }
    }

    @Override
    public void onBulletHit(BulletHitBotEvent e) {
        if (e.getVictimId() == targetId) dmgDealt += e.getDamage();
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        // one of our bullets cancelled an enemy bullet: forget that wave
        EWave w = matchWave(e.getHitBullet());
        if (w != null) eWaves.remove(w);
    }

    @Override
    public void onHitBot(HitBotEvent e) {
        lastCollisionTurn = getTurnNumber();
        flipDirection(true);   // collision: back off instead of trading ram damage
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        flipDirection(true);
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        BulletState b = e.getBullet();
        gainFromHit += 3 * b.getPower();      // the shooter gets energy back when it hits
        if (getEnemyCount() == 1) {
            EWave w = matchWave(b);
            if (w != null) {
                recordSurf(w, getX(), getY(), 1.0);
                eWaves.remove(w);
            }
        } else {
            flipDirection(false);
        }
    }

    /** Which enemy wave does this bullet belong to? (same speed, and it is where the wave's edge is) */
    private EWave matchWave(BulletState b) {
        int now = getTurnNumber();
        EWave best = null;
        double bestErr = 60;
        for (EWave w : eWaves) {
            if (Math.abs(w.speed - b.getSpeed()) > 0.3) continue;
            double err = Math.abs(Math.hypot(b.getX() - w.ox, b.getY() - w.oy) - waveRadius(w, now));
            if (err < bestErr) {
                bestErr = err;
                best = w;
            }
        }
        return best;
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
