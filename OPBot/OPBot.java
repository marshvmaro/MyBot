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

/*
 * quick rundown of what this bot does
 *
 * 1v1: wave surfing for movement, guess factor gun (plus a few simpler guns) for shooting
 *   - every time the enemy fires we make a "wave" (an expanding circle = their bullet)
 *   - we dodge by picking whichever orbit direction ends us somewhere we rarely get hit
 *   - for aiming we run 4 guns at once with pretend bullets and use whichever is hitting best
 *
 * melee: minimum risk movement, just drive to whatever nearby spot is furthest from everyone
 *
 * angles are degrees, 0 = east, counter clockwise is positive (same as atan2)
 */
public class OPBot extends Bot {

    private static final double BOT_HALF = 18.0;     // bots are 36px wide so 18 to each edge, used for walls and hit checks
    private static final int GUNS = 4;               // head on, linear, circular, guess factor
    private static final int BINS = 31;              // guess factor range -1..1 cut into 31 slices
    private static final int CENTER = BINS / 2;      // middle slice = 0 = straight at the enemy

    // tuning knobs
    private static final double PASS_WEIGHT = 0.15;  // a wave that missed us still teaches a little, 15% of a real hit
    private static final int WAVE_OFFSET = -1;       // we spot shots a tick late so shift the wave back, tested on 1.4.0

    // target tracking
    private int targetId = -1;                       // who were shooting at, -1 = nobody
    private double tx, ty, tDir, tSpeed, tEnergy;    // target pos, heading, speed (negative if reversing), energy
    private double tTurnRate = 0;                    // degrees per tick the target is turning, circular gun uses it
    private double prevDir = 0;                      // heading at last scan so we can get the turn rate
    private int lastScanTurn = -100;                 // when we last saw the target, if its old we spin the radar
    private int lastLatDir = 1;                      // which way they were last strafing (+1 / -1)
    private int lastChangeTurn = 0;                  // last time they flipped direction or changed speed a lot

    // enemy shot detection for 1v1 surfing
    private int scanTurn = -100;                     // turn of the newest scan
    private int prevScanTurn = -100;                 // turn of the one before
    private double prevEnergy = 100;                 // their energy last scan, a drop = they probably shot
    private double pTx, pTy;                         // where they were last scan
    private double pMeX, pMeY;                       // where we were last scan (what they aimed at)
    private int myLatDirPrev = 1, myLatDir = 1;      // which way were strafing around them, now and last scan
    private double dmgDealt = 0, gainFromHit = 0;    // energy changes we can explain (our hits / their hits), so we dont count them as shots
    private int lastCollisionTurn = -100;            // ramming costs energy too, dont mistake it for a shot

    // every bot weve seen: id -> {x, y, turnSeen, energy}
    private final Map<Integer, double[]> bots = new HashMap<>();

    // movement
    private int orbitDir = 1;                        // which way were circling
    private int flipCooldown = 0;                    // so we dont flip every single tick and jitter
    private double prefDist = 380;                   // distance we want to stay at, randomized so its harder to predict
    private double destX, destY;                     // melee destination
    private int destTurn = -100;                     // when we picked it

    // radar for melee
    private int sweepUntil = -1;                     // full spin until this turn
    private int lastSweepEnd = 0;                    // when the last one finished

    // enemy waves and surf stats
    private static class EWave { // one enemy bullet in the air
        double ox, oy, speed, directAngle, mea; // where it started, speed, angle straight at us, biggest angle we could escape by
        int fireTurn, latDir; // when it was fired, which way we were strafing
    }
    private final List<EWave> eWaves = new ArrayList<>();  // all waves currently out there
    private final double[] surf = new double[BINS];        // danger per guess factor, bigger = got hit there more. never reset so it keeps learning

    // virtual guns
    private final double[] gunScore = {0.0, 0.0, 0.01, 0.0}; // running hit score per gun, circular gets 0.01 so it wins ties early on
    private final List<VBullet> vbullets = new ArrayList<>(); // pretend bullets waiting to be scored

    // guess factor buffers. guess factor = where the enemy ended up compared to straight at them,
    // 0 = stayed on the line, +1 = max escape in the direction they were strafing, -1 = max the other way.
    // each buffer splits the data by different things so we can vote between them.
    private final double[][][] gf = {
            new double[16][BINS],   // 0: flight time x sideways speed
            new double[36][BINS],   // 1: sideways speed x wall room x acceleration
            new double[36][BINS],   // 2: flight time x advancing speed x time since change
            new double[1][BINS]     // 3: everything lumped together, forgets fast
    };
    private static final double[] GF_DECAY = {0.995, 0.99, 0.993, 0.97}; // closer to 1 = remembers longer
    private static final double[] GF_WEIGHT = {1.0, 1.0, 1.0, 0.5};      // buffer 3 is the vaguest so it gets half a vote

    private static class VBullet { // a bullet we never actually fired, we just check later who would have hit
        double ox, oy, speed; // start point and speed
        int fireTurn; // when it "left"
        double[] angles = new double[GUNS]; // where each gun was aiming
        double abs; // straight line angle to the enemy at fire time
        int latDir; // enemy strafe direction at fire time
        double mea; // max escape angle for this bullet speed
        int[] seg = new int[4]; // which segment of each buffer to save the result into
    }

    public static void main(String[] args) {
        new OPBot().start(); // connects to the server
    }

    OPBot() {
        super(BotInfo.builder()
                .setName("OPBot")
                .setVersion("3.1.4.1.5.9.2.6.5.3.5")
                .addAuthor("me")
                .build());
    }

    @Override
    // runs at the start of every round, this is basically the main loop
    public void run() {
        // by default the gun and radar turn with the body which is annoying, so detach them
        setAdjustGunForBodyTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForBodyTurn(true);

        setBodyColor(Color.fromRgb(0, 0, 0));
        setBulletColor(Color.fromRgb(0, 0, 0));
        setTurretColor(Color.fromRgb(0, 0, 0));
        setScanColor(Color.fromRgb(28, 101, 27));
        setRadarColor(Color.fromRgb(28, 101, 27));

        // reset round stuff. gunScore, gf and surf stay on purpose so we keep learning between rounds
        targetId = -1;
        lastScanTurn = -100;
        scanTurn = -100;
        prevScanTurn = -100;
        orbitDir = Math.random() < 0.5 ? 1 : -1; // random start direction
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

        while (isRunning()) { // one loop = one tick
            if (flipCooldown > 0) flipCooldown--;

            doRadar();
            if (getTurnNumber() - lastScanTurn > 20) {
                targetId = -1; // haven't seen them in ages, give up on them
            }

            processEnemyShots();
            updateWaves();
            doMovement();
            go(); // nothing actually happens until go(), everything above just queues commands
        }
    }

    // 1v1: lock on in onScannedBot and only spin if we lose them
    // melee: spin until we have a target, then lock, but do a full spin every ~25 ticks to refresh everyones position
    private void doRadar() {
        int now = getTurnNumber();
        boolean lost = now - lastScanTurn > 2; // fell out of the beam
        if (getEnemyCount() > 1) { // melee
            if (now < sweepUntil || targetId == -1 || lost) {
                setTurnRadarLeft(Double.POSITIVE_INFINITY);
            } else if (now - lastSweepEnd > 25) {
                sweepUntil = now + 9;   // a full turn takes 8 ticks, +1 to be safe
                lastSweepEnd = now + 9;
                setTurnRadarLeft(Double.POSITIVE_INFINITY);
            }
        } else if (lost) { // 1v1
            setTurnRadarLeft(Double.POSITIVE_INFINITY);
        }
    }

    // how we notice the enemy shooting: firing costs energy equal to the bullet power (0.1 - 3).
    // so if their energy dropped by that much and we cant explain it any other way, they shot.
    private void processEnemyShots() {
        int now = getTurnNumber();
        if (targetId == -1 || scanTurn != now) return; // need a fresh scan

        // start from last energy, take off damage we did, add back what they got for hitting us
        double expected = prevEnergy - dmgDealt + gainFromHit;
        double drop = expected - tEnergy; // whats left is probably a shot
        dmgDealt = 0;
        gainFromHit = 0;

        boolean collided = now - lastCollisionTurn <= 1;
        // also skip if the last scan was too long ago, too much could have happened in between
        if (drop >= 0.09 && drop <= 3.01 && !collided && scanTurn - prevScanTurn <= 2) {
            if (getEnemyCount() == 1) {
                spawnWave(drop);
            } else if (Math.random() < 0.8) {
                flipDirection(false); // melee, no waves, just switch direction
            }
        }

        // this scan becomes the "previous" one for next time
        prevEnergy = tEnergy;
        prevScanTurn = scanTurn;
        pTx = tx;
        pTy = ty;
        pMeX = getX();
        pMeY = getY();
        myLatDirPrev = myLatDir;
        // our sideways speed relative to them, the sign tells us which way were going around them
        double lat = getSpeed() * Math.sin(Math.toRadians(getDirection() - angleTo(tx, ty, getX(), getY())));
        if (Math.abs(lat) > 0.1) myLatDir = lat > 0 ? 1 : -1; // ignore it if were barely moving sideways
    }

    // the shot happened from where they were and was aimed at where we were, both one scan ago
    private void spawnWave(double power) {
        EWave w = new EWave();
        w.ox = tx;
        w.oy = ty;
        w.speed = 20 - 3 * power; // standard bullet speed formula
        w.fireTurn = scanTurn + WAVE_OFFSET;
        w.directAngle = angleTo(w.ox, w.oy, pMeX, pMeY);
        w.latDir = myLatDirPrev;
        // furthest sideways angle we could possibly get to before the bullet lands (our top speed is 8)
        w.mea = Math.toDegrees(Math.asin(8.0 / w.speed));
        eWaves.add(w);
    }

    // how far out the front of the wave is right now
    private double waveRadius(EWave w, int turn) {
        return w.speed * (turn - w.fireTurn);
    }

    // throw away waves that went past us, and learn a bit from the ones that missed
    private void updateWaves() {
        int now = getTurnNumber();
        Iterator<EWave> it = eWaves.iterator();
        while (it.hasNext()) {
            EWave w = it.next();
            double d = Math.hypot(getX() - w.ox, getY() - w.oy);
            // past us = front is beyond our far edge, plus 4px of slack
            if (waveRadius(w, now) > d + BOT_HALF + 4) {
                if (PASS_WEIGHT > 0) recordSurf(w, getX(), getY(), PASS_WEIGHT); // we were fine standing here
                it.remove();
            }
        }
    }

    // turns a point in space into a guess factor for this wave (-1..1).
    // multiplying by latDir means + is always "the way we were already going"
    private double gfOf(EWave w, double px, double py) {
        double offset = norm(angleTo(w.ox, w.oy, px, py) - w.directAngle);
        return clamp(offset * w.latDir / w.mea, -1, 1);
    }

    // add danger around the bin we were in. smeared over neighbours (gaussian) because if
    // you get hit at one spot the spots right next to it are almost as bad
    private void recordSurf(EWave w, double px, double py, double weight) {
        int idx = (int) Math.round(gfOf(w, px, py) * CENTER + CENTER);
        for (int b = 0; b < BINS; b++) {
            double z = (b - idx) / 1.6;
            surf[b] += weight * Math.exp(-0.5 * z * z);
        }
    }

    private void doMovement() {
        if (targetId == -1) {
            // nobody found yet, just drive a wide curve while the radar looks
            double travel = smooth(getX(), getY(), getDirection() + 15 * orbitDir, orbitDir);
            driveToward(travel, 150);
        } else if (getEnemyCount() == 1) {
            surfMove();
        } else {
            meleeMove();
        }
    }

    // 1v1 wave surfing

    // idea: simulate "keep orbiting clockwise" and "keep orbiting counter clockwise",
    // see how dangerous the spot is where the next waves would catch us, go with the safer one
    private void surfMove() {
        double pref = prefDist;
        if (getEnergy() < tEnergy - 30) pref += 100; // way behind on energy so stay further away

        int dir = orbitDir;
        List<EWave> ws = upcomingWaves(2); // only care about the next two
        if (!ws.isEmpty()) {
            double dPlus = predictDanger(ws, 1, pref);   // counter clockwise
            double dMinus = predictDanger(ws, -1, pref); // clockwise
            if (dPlus < dMinus - 1e-9) dir = 1;
            else if (dMinus < dPlus - 1e-9) dir = -1;
            orbitDir = dir;
        } else if (flipCooldown == 0 && Math.random() < 0.03) {
            flipDirection(false); // nothing coming, but switch it up every so often anyway
            dir = orbitDir;
        }

        double travel = orbitAngle(getX(), getY(), tx, ty, dir, pref);
        travel = smooth(getX(), getY(), travel, dir);
        driveToward(travel, 120);
    }

    // the next n waves to hit us, soonest first
    private List<EWave> upcomingWaves(int n) {
        int now = getTurnNumber();
        List<EWave> out = new ArrayList<>(eWaves);
        out.sort((a, b) -> Double.compare(timeLeft(a, now), timeLeft(b, now)));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    // roughly how many ticks until the wave reaches us
    private double timeLeft(EWave w, int now) {
        double d = Math.hypot(getX() - w.ox, getY() - w.oy);
        return (d - waveRadius(w, now)) / w.speed;
    }

    // heading that circles the enemy. 90 degrees off the line to them = perfect circle,
    // the k bit bends it in if were too far or out if were too close
    private double orbitAngle(double x, double y, double ex, double ey, int dir, double pref) {
        double theta = angleTo(ex, ey, x, y);
        double d = Math.hypot(x - ex, y - ey);
        double k = clamp((d - pref) / 5.0, -45, 45);
        return theta + dir * (90 + k);
    }

    // simulates us driving (with the real turn rate / accel / braking rules) until each wave
    // reaches us, then adds up how often weve been hit at those spots. lower = safer
    private double predictDanger(List<EWave> ws, int dir, double pref) {
        int now = getTurnNumber();
        EWave w0 = ws.get(0); // circle around the first waves origin
        double px = getX(), py = getY(), ph = getDirection(), pv = getSpeed();
        boolean[] done = new boolean[ws.size()];
        int remaining = ws.size();
        double total = 0;
        double lo = BOT_HALF, hiX = getArenaWidth() - BOT_HALF, hiY = getArenaHeight() - BOT_HALF;

        for (int t = 1; t <= 160 && remaining > 0; t++) {
            double want = smooth(px, py, orbitAngle(px, py, w0.ox, w0.oy, dir, pref), dir);
            double turn = norm(want - ph);
            int md = 1; // 1 = forward
            if (Math.abs(turn) > 90) { // quicker to just reverse
                turn = norm(turn - 180);
                md = -1;
            }
            double maxT = 10 - 0.75 * Math.abs(pv); // faster = can't turn as sharply
            ph = norm(ph + clamp(turn, -maxT, maxT));
            pv += (pv * md < 0) ? 2 * md : md; // braking is 2/tick, speeding up is 1/tick
            pv = clamp(pv, -8, 8);
            px = clamp(px + Math.cos(Math.toRadians(ph)) * pv, lo, hiX);
            py = clamp(py + Math.sin(Math.toRadians(ph)) * pv, lo, hiY);

            for (int i = 0; i < ws.size(); i++) {
                if (done[i]) continue;
                EWave w = ws.get(i);
                // wave hits the first tick its radius covers the distance to our spot
                if (Math.hypot(px - w.ox, py - w.oy) <= waveRadius(w, now + t)) {
                    done[i] = true;
                    remaining--;
                    int idx = (int) Math.round(gfOf(w, px, py) * CENTER + CENTER);
                    total += surf[idx] * (i == 0 ? 1.0 : 0.5); // second wave matters less
                }
            }
        }
        return total;
    }

    // melee minimum risk

    private void meleeMove() {
        double x = getX(), y = getY();
        int now = getTurnNumber();
        if (now - destTurn > 14 || Math.hypot(destX - x, destY - y) < 25) { // old or already there
            pickDestination();
        }
        double d = Math.hypot(destX - x, destY - y);
        driveToward(angleTo(x, y, destX, destY), Math.max(20, Math.min(d, 150)));
    }

    // throw 36 random points around us, keep whichever has the lowest risk
    private void pickDestination() {
        double x = getX(), y = getY();
        double best = Double.MAX_VALUE;
        double bx = x, by = y; // fall back to staying put
        double margin = 35;
        for (int i = 0; i < 36; i++) {
            double ang = Math.toRadians(i * 10 + Math.random() * 10); // one per 10 degree slice + jitter
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

    // how bad is it to stand at (px, py). every enemy adds risk that blows up as we get closer
    // (inverse square), more if the move takes us toward them, more if theyre high energy
    private double riskAt(double px, double py, double x, double y) {
        int now = getTurnNumber();
        double risk = 0;
        for (double[] b : bots.values()) {
            if (now - b[2] > 40) continue; // stale info, ignore
            double d2 = Math.max(400, (px - b[0]) * (px - b[0]) + (py - b[1]) * (py - b[1])); // floor so we dont divide by ~0
            double a1 = angleTo(b[0], b[1], x, y);
            double a2 = angleTo(b[0], b[1], px, py);
            double perp = 1 + Math.abs(Math.cos(Math.toRadians(a1 - a2)));  // toward them is worse than sideways
            double weight = 1 + b[3] / 50.0;
            risk += weight * perp * 90000 / d2;
        }
        double edge = Math.min(Math.min(px, getArenaWidth() - px), Math.min(py, getArenaHeight() - py));
        if (edge < 120) risk += (120 - edge) / 120 * 1.5; // dont hug walls
        double cx = Math.min(px, getArenaWidth() - px), cy = Math.min(py, getArenaHeight() - py);
        if (cx < 150 && cy < 150) risk += 2; // corners are traps
        return risk + Math.random() * 0.05; // tiny noise so ties dont always go the same way
    }

    // shared driving helpers

    // turn toward the heading, but if its more than 90 off just turn less and drive backwards
    private void driveToward(double travel, double distance) {
        double delta = norm(travel - getDirection());
        int dir = 1;
        if (Math.abs(delta) > 90) {
            delta = norm(delta - 180);
            dir = -1;
        }
        setTurnLeft(delta);
        setForward(distance * dir); // negative distance = reverse
    }

    // wall smoothing: if driving this way would run us into a wall, bend the heading
    // toward the enemy side 3 degrees at a time until the path ahead is clear
    private double smooth(double x, double y, double angle, int dir) {
        for (int i = 0; i <= 60; i++) { // 60 * 3 = up to 180 degrees
            double a = angle + dir * i * 3;
            if (insideField(x, y, a)) return a;
        }
        return angle; // couldn't find anything, just use the original
    }

    // look 140px ahead and make sure were still 28px from every wall
    private boolean insideField(double fromX, double fromY, double angle) {
        double look = 140;
        double margin = 28;
        double x = fromX + Math.cos(Math.toRadians(angle)) * look;
        double y = fromY + Math.sin(Math.toRadians(angle)) * look;
        return x > margin && x < getArenaWidth() - margin
                && y > margin && y < getArenaHeight() - margin;
    }

    // reverse the orbit. force = ignore the cooldown (wall hit, collision)
    private void flipDirection(boolean force) {
        if (force || flipCooldown == 0) {
            orbitDir = -orbitDir;
            flipCooldown = 10;
            prefDist = 320 + Math.random() * 130; // new distance each flip
        }
    }

    @Override
    // radar saw a bot. this is where most of the gun stuff happens, in order:
    // 1) log the bot, maybe make it the target  2) update what we know about the target
    // 3) keep the radar on it  4) score old virtual bullets  5) pick bullet power
    // 6) work out each guns aim and make new virtual bullets  7) aim + shoot with the best gun
    public void onScannedBot(ScannedBotEvent e) {
        int id = e.getScannedBotId();
        int now = getTurnNumber();
        double dist = distanceTo(e.getX(), e.getY());

        bots.put(id, new double[]{e.getX(), e.getY(), now, e.getEnergy()});

        double currentTargetDist = (targetId == -1) ? Double.MAX_VALUE : distanceTo(tx, ty);

        // stick with the current target unless theres none, someone is way closer,
        // or someone is way weaker and not far off
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
            vbullets.clear(); // all of this was for the old target
            eWaves.clear();
        }
        if (id != targetId) return; // not our target, nothing else to do

        // update target info
        int dt = Math.max(1, now - lastScanTurn);
        tTurnRate = norm(e.getDirection() - prevDir) / dt;
        prevDir = e.getDirection();
        double prevSpeed = tSpeed; // need the old one for acceleration below

        tx = e.getX();
        ty = e.getY();
        tDir = e.getDirection();
        tSpeed = e.getSpeed();
        tEnergy = e.getEnergy();
        lastScanTurn = now;
        scanTurn = now;

        // radar lock (always in 1v1, in melee unless were mid sweep).
        // we deliberately swing a bit PAST the enemy so the beam sweeps back over them
        // next tick, that way we see them every single tick
        if (getEnemyCount() == 1 || now >= sweepUntil) {
            double neededTurn = radarBearingTo(tx, ty);
            double angularWidth = Math.toDegrees(2 * Math.atan(BOT_HALF / Math.max(dist, 1)));
            double overshoot = Math.copySign(angularWidth * 0.75 + 2, neededTurn);
            setTurnRadarLeft(neededTurn + overshoot);
        }

        // score any virtual bullets that have arrived
        resolveVirtualBullets();

        // bullet power: big when close (easy to hit), small when far.
        // bigger bullets are also slower so theyre easier to dodge
        double power;
        if (dist < 150) power = 3;
        else if (dist < 350) power = 2.5;
        else if (dist < 600) power = 1.8;
        else power = 1.2;
        if (getEnemyCount() > 1 && dist > 500) power = Math.min(power, 1.5);
        if (getEnergy() < 20) power = Math.min(power, 1.5); // running low, be careful
        if (getEnergy() < 8) power = Math.min(power, 0.8);
        // dont waste energy on overkill (damage is 4p, or 6p - 2 above power 1)
        double killPower = tEnergy <= 4 ? tEnergy / 4 : (tEnergy + 2) / 6;
        power = Math.min(power, Math.max(0.1, killPower + 0.05));
        power = Math.max(0.1, Math.min(3, power)); // stay in the legal range

        double bulletSpeed = calcBulletSpeed(power);

        // situation info for the guess factor gun
        double abs = angleTo(getX(), getY(), tx, ty); // straight line angle to them
        // lv = how fast theyre moving sideways relative to us, thats the part that makes them hard to hit
        double lv = tSpeed * Math.sin(Math.toRadians(tDir - abs));
        if (Math.abs(lv) > 0.5) {
            int s = lv > 0 ? 1 : -1;
            if (s != lastLatDir) lastChangeTurn = now; // they changed direction
            lastLatDir = s;
        }
        if (Math.abs(tSpeed - prevSpeed) > 1.5) lastChangeTurn = now; // big speed change counts too
        int latDir = lastLatDir;
        int[] seg = segments(dist, bulletSpeed, lv, prevSpeed, now);
        double mea = Math.toDegrees(Math.asin(8.0 / bulletSpeed));

        // aim angle for each gun
        double[] aim = new double[GUNS];
        aim[0] = abs;                                                        // head on
        double[] lin = predict(0, bulletSpeed);
        aim[1] = angleTo(getX(), getY(), lin[0], lin[1]);                    // linear (they keep going straight)
        double[] cir = predict(tTurnRate, bulletSpeed);
        aim[2] = angleTo(getX(), getY(), cir[0], cir[1]);                    // circular (they keep turning)

        // guess factor gun: add up what every buffer has seen in this situation,
        // take the tallest bin = most common outcome, aim where that bin points
        double[] comb = combinedStats(seg);
        int bestBin = CENTER;
        for (int b = 0; b < BINS; b++) {
            if (comb[b] > comb[bestBin] + 1e-9) bestBin = b;
        }
        double gfAim = (bestBin - CENTER) / (double) CENTER;
        aim[3] = abs + latDir * gfAim * mea;                                 // guess factor
        for (int g = 0; g < GUNS; g++) aim[g] = clampAim(aim[g], dist);      // no aiming outside the arena

        // save a virtual bullet so we can grade every gun later
        VBullet vb = new VBullet();
        vb.ox = getX();
        vb.oy = getY();
        vb.speed = bulletSpeed;
        vb.fireTurn = now + 1; // real bullets leave next tick
        vb.abs = abs;
        vb.latDir = latDir;
        vb.mea = mea;
        System.arraycopy(seg, 0, vb.seg, 0, 4);
        System.arraycopy(aim, 0, vb.angles, 0, GUNS);
        vbullets.add(vb);
        if (vbullets.size() > 200) vbullets.remove(0); // cap it so it doesnt grow forever

        // use the gun thats been hitting best, ties go to circular
        int best = 2;
        for (int g = 0; g < GUNS; g++) {
            if (gunScore[g] > gunScore[best]) best = g;
        }

        // aim and fire
        double gunTurn = norm(aim[best] - getGunDirection());
        setTurnGunLeft(gunTurn);
        // only shoot when the gun is already pointing at their body, is cool, and we can afford it
        double tolerance = Math.toDegrees(Math.atan(BOT_HALF / Math.max(dist, 1)));
        if (Math.abs(gunTurn) <= tolerance && getGunHeat() == 0 && getEnergy() > power) {
            setFire(power);
        }
    }

    // works out which "slice" of each guess factor buffer fits the current situation.
    // the point is that an enemy strafing fast at long range acts differently from one
    // sitting still up close, so we keep their stats separate instead of averaging them
    private int[] segments(double dist, double bulletSpeed, double lv, double prevSpeed, int now) {
        double alv = Math.abs(lv);
        double flight = dist / bulletSpeed; // how many ticks the bullet takes to get there
        int ft = flight < 10 ? 0 : (flight < 20 ? 1 : (flight < 30 ? 2 : 3));
        int lvb = alv < 1 ? 0 : (alv < 3 ? 1 : (alv < 5.5 ? 2 : 3));

        double abs = angleTo(getX(), getY(), tx, ty);
        double adv = -tSpeed * Math.cos(Math.toRadians(tDir - abs));   // positive = coming toward us
        int advb = adv < -2 ? 0 : (adv < 2 ? 1 : 2);

        double moveAngle = tSpeed >= 0 ? tDir : tDir + 180; // which way theyre really going
        double room = rayToWall(tx, ty, moveAngle) / (8 * flight + 1); // wall room vs how far they could go
        int wallb = room < 0.6 ? 0 : (room < 1.2 ? 1 : 2);

        double accel = Math.abs(tSpeed) - Math.abs(prevSpeed);
        int accb = accel < -0.5 ? 0 : (accel > 0.5 ? 2 : 1);

        int since = now - lastChangeTurn;
        int sinceb = since < 12 ? 0 : (since < 35 ? 1 : 2);

        // each index is like a multi digit number, e.g. buffer 1 = 4 x 3 x 3 = 36 slots
        return new int[]{
                ft * 4 + lvb,
                (lvb * 3 + wallb) * 3 + accb,
                (ft * 3 + advb) * 3 + sinceb,
                0 // buffer 3 only has one slot
        };
    }

    // every buffer votes. each one is normalized so theyre comparable, and the total starts
    // at 1 so a segment with hardly any data barely gets a say
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

    // how far you can go from (x, y) in this direction before hitting a wall (inset by half a bot).
    // basically a ray cast, check each wall were heading toward and keep the closest
    private double rayToWall(double x, double y, double angle) {
        double dx = Math.cos(Math.toRadians(angle)), dy = Math.sin(Math.toRadians(angle));
        double t = 1e9;
        if (dx > 1e-9) t = Math.min(t, (getArenaWidth() - BOT_HALF - x) / dx);
        else if (dx < -1e-9) t = Math.min(t, (BOT_HALF - x) / dx);
        if (dy > 1e-9) t = Math.min(t, (getArenaHeight() - BOT_HALF - y) / dy);
        else if (dy < -1e-9) t = Math.min(t, (BOT_HALF - y) / dy);
        return Math.max(0, t);
    }

    // enemies cant be outside the arena so if the aim point is, push it back inside
    // and return the angle to the pushed point
    private double clampAim(double angle, double dist) {
        double px = clamp(getX() + Math.cos(Math.toRadians(angle)) * dist, BOT_HALF, getArenaWidth() - BOT_HALF);
        double py = clamp(getY() + Math.sin(Math.toRadians(angle)) * dist, BOT_HALF, getArenaHeight() - BOT_HALF);
        return angleTo(getX(), getY(), px, py);
    }

    // walks the enemy forward tick by tick (constant speed, optional constant turn) until our
    // bullet could reach them. turnRate 0 = straight line, otherwise a curved path
    private double[] predict(double turnRate, double bulletSpeed) {
        double px = tx, py = ty, heading = tDir;
        for (int t = 1; t <= 100; t++) {
            heading += turnRate;
            double r = Math.toRadians(heading);
            px += Math.cos(r) * tSpeed;
            py += Math.sin(r) * tSpeed;
            px = clamp(px, BOT_HALF, getArenaWidth() - BOT_HALF);
            py = clamp(py, BOT_HALF, getArenaHeight() - BOT_HALF);
            if (distanceTo(px, py) <= bulletSpeed * t) break; // bullet would be there by now
        }
        return new double[]{px, py};
    }

    // a virtual bullet "arrives" once it has traveled as far as the enemy is from where it started.
    // at that point we (a) check which guns would have hit and (b) record where the enemy
    // actually ended up so the guess factor buffers learn from it
    private void resolveVirtualBullets() {
        Iterator<VBullet> it = vbullets.iterator();
        while (it.hasNext()) {
            VBullet vb = it.next();
            int age = getTurnNumber() - vb.fireTurn;
            if (age > 120) { // never arrived for some reason, drop it
                it.remove();
                continue;
            }
            double d = Math.hypot(tx - vb.ox, ty - vb.oy);
            if (vb.speed * age >= d) { // it got there
                double actual = angleTo(vb.ox, vb.oy, tx, ty);
                double tol = Math.toDegrees(Math.atan(BOT_HALF / Math.max(d, 1)));

                // grade the guns. old results fade (x0.97) so it follows recent form
                for (int g = 0; g < GUNS; g++) {
                    boolean hit = Math.abs(norm(actual - vb.angles[g])) <= tol;
                    gunScore[g] = gunScore[g] * 0.97 + (hit ? 1 : 0);
                }

                // turn the real position into a guess factor + bin, then add a gaussian bump
                // there in the segments that applied when this bullet was made
                double offset = norm(actual - vb.abs);
                double g = clamp(offset * vb.latDir / vb.mea, -1, 1);
                int idx = (int) Math.round(g * CENTER + CENTER);
                for (int k = 0; k < gf.length; k++) {
                    double[] st = gf[k][vb.seg[k]];
                    for (int b = 0; b < BINS; b++) {
                        double z = (b - idx) / 1.5;
                        st[b] = st[b] * GF_DECAY[k] + Math.exp(-0.5 * z * z); // fade the old stuff, add the new
                    }
                }

                it.remove();
            }
        }
    }

    @Override
    public void onBotDeath(BotDeathEvent e) {
        bots.remove(e.getVictimId());
        if (e.getVictimId() == targetId) { // our target died, need a new one
            targetId = -1;
            vbullets.clear();
            eWaves.clear();
        }
    }

    @Override
    public void onBulletHit(BulletHitBotEvent e) {
        // remember the damage so the energy drop isnt mistaken for them firing
        if (e.getVictimId() == targetId) dmgDealt += e.getDamage();
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        // our bullet cancelled one of theirs so that wave is gone
        EWave w = matchWave(e.getHitBullet());
        if (w != null) eWaves.remove(w);
    }

    @Override
    public void onHitBot(HitBotEvent e) {
        lastCollisionTurn = getTurnNumber(); // so the energy loss isnt read as a shot
        flipDirection(true); // back off instead of trading ram damage
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        flipDirection(true);
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        BulletState b = e.getBullet();
        gainFromHit += 3 * b.getPower(); // shooter gets 3x the power back when it hits
        if (getEnemyCount() == 1) {
            EWave w = matchWave(b);
            if (w != null) {
                recordSurf(w, getX(), getY(), 1.0); // full weight, this spot got us hit
                eWaves.remove(w);
            }
        } else {
            flipDirection(false); // melee, just change direction
        }
    }

    // figure out which wave a bullet came from. we match on speed (almost the same) and on
    // how close the bullet is to the waves edge, closest match wins, null if nothing fits
    private EWave matchWave(BulletState b) {
        int now = getTurnNumber();
        EWave best = null;
        double bestErr = 60; // anything worse than this isnt a match
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

    // angle (degrees, 0 = east) from point 1 to point 2
    private static double angleTo(double x1, double y1, double x2, double y2) {
        return Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
    }

    // wraps an angle into -180..180, so 350 and -10 mean the same thing
    // and "how far do I turn" is always the short way round
    private static double norm(double angle) {
        while (angle > 180) angle -= 360;
        while (angle <= -180) angle += 360;
        return angle;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
