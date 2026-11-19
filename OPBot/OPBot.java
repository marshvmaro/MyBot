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

public class OPBot extends Bot { // our bot, extends the tank royale base class

    // Referenced in: clampAim(), onScannedBot(), predict(), predictDanger(), rayToWall(), resolveVirtualBullets(), updateWaves().
    private static final double BOT_HALF = 18.0;     // half the bot's 36-pixel width, used for wall and hit checks
    // Referenced in: onScannedBot(), resolveVirtualBullets().
    private static final int GUNS = 4;               // number of aiming methods being tested against each other
    // Referenced in: combinedStats(), onScannedBot(), recordSurf(), resolveVirtualBullets().
    private static final int BINS = 31;              // number of slots used to track where enemy movement tends to go
    // Referenced in: onScannedBot(), predictDanger(), recordSurf(), resolveVirtualBullets().
    private static final int CENTER = BINS / 2;      // center slot, representing a shot aimed directly at the enemy

    // tuning knobs
    // Referenced in: updateWaves().
    private static final double PASS_WEIGHT = 0.15;  // how much a wave that missed us counts in surf stats
    // Referenced in: spawnWave().
    private static final int WAVE_OFFSET = -1;       // timing fix, measured against real hits in tank royale 1.4.0

    // target tracking
    // Referenced in: doMovement(), doRadar(), onBotDeath(), onBulletHit(), onScannedBot(), processEnemyShots(), run().
    private int targetId = -1;                       // id of who were shooting at, -1 for nobody
    // Referenced in: onScannedBot(), predict(), processEnemyShots(), resolveVirtualBullets(), segments(), spawnWave(), surfMove().
    private double tx, ty, tDir, tSpeed, tEnergy;    // target x y heading speed and energy
    // Referenced in: onScannedBot().
    private double tTurnRate = 0;                    // how fast the target is turning per tick
    // Referenced in: onScannedBot().
    private double prevDir = 0;                      // target heading at the last scan
    // Referenced in: doRadar(), onScannedBot(), run().
    private int lastScanTurn = -100;                 // turn we last scanned the target
    // Referenced in: onScannedBot().
    private int lastLatDir = 1;                      // enemys last nonzero sideways direction
    // Referenced in: onScannedBot(), segments().
    private int lastChangeTurn = 0;                  // last time enemy changed direction or speed a lot

    // enemy shot detection for 1v1 surfing
    // Referenced in: onScannedBot(), processEnemyShots(), run(), spawnWave().
    private int scanTurn = -100;                     // turn of the newest scan of the target
    // Referenced in: onScannedBot(), processEnemyShots(), run().
    private int prevScanTurn = -100;                 // turn of the scan before that
    // Referenced in: onScannedBot(), processEnemyShots().
    private double prevEnergy = 100;                 // target energy at the previous scan
    // Referenced in: onScannedBot(), processEnemyShots().
    private double pTx, pTy;                         // target position at the previous scan
    // Referenced in: processEnemyShots(), spawnWave().
    private double pMeX, pMeY;                       // our position at the previous scan
    // Referenced in: processEnemyShots(), spawnWave().
    private int myLatDirPrev = 1, myLatDir = 1;      // our sideways direction relative to the enemy
    // Referenced in: onBulletHit(), onScannedBot(), processEnemyShots(), run().
    private double dmgDealt = 0, gainFromHit = 0;    // energy changes explained by bullet hits
    // Referenced in: onHitBot(), processEnemyShots(), run().
    private int lastCollisionTurn = -100;            // last turn we bumped into a bot

    // every bot weve seen, id to x y turnSeen energy
    // Referenced in: onBotDeath(), onScannedBot(), riskAt(), run().
    private final Map<Integer, double[]> bots = new HashMap<>(); // lookup of last known bot info

    // movement
    // Referenced in: doMovement(), flipDirection(), run(), surfMove().
    private int orbitDir = 1;                        // which way were circling
    // Referenced in: flipDirection(), run(), surfMove().
    private int flipCooldown = 0;                    // ticks before we can flip direction again
    // Referenced in: flipDirection(), surfMove().
    private double prefDist = 380;                   // distance we like to keep from the enemy
    // Referenced in: meleeMove(), pickDestination().
    private double destX, destY;                     // melee destination
    // Referenced in: meleeMove(), pickDestination(), run().
    private int destTurn = -100;                     // turn we picked the current destination

    // radar for melee
    // Referenced in: doRadar(), onScannedBot(), run().
    private int sweepUntil = -1;                     // keep sweeping until this turn
    // Referenced in: doRadar(), run().
    private int lastSweepEnd = 0;                    // turn the last sweep ended

    // enemy waves and surf stats
    private static class EWave { // one enemy bullet wave
        // Referenced in: spawnWave(), waveRadius(), timeLeft(), predictDanger(), matchWave(), and updateWaves().
        double ox, oy, speed, directAngle, mea; // origin, speed, angle straight at us, max escape angle
        // Referenced in: spawnWave(), waveRadius(), predictDanger(), and matchWave().
        int fireTurn, latDir; // when it was fired and our sideways direction then
    }
    // Referenced in: matchWave(), onBotDeath(), onBulletHitBullet(), onHitByBullet(), onScannedBot(), run(), spawnWave(), upcomingWaves(), updateWaves().
    private final List<EWave> eWaves = new ArrayList<>(); // waves currently in the air
    // Referenced in: predictDanger(), recordSurf().
    private final double[] surf = new double[BINS];       // stores how dangerous each movement angle has been based on past hits

    // virtual guns
    // Referenced in: onScannedBot(), resolveVirtualBullets().
    private final double[] gunScore = {0.0, 0.0, 0.01, 0.0}; // running hit score per gun, circular gets a tiny head start
    // Referenced in: onBotDeath(), onScannedBot(), resolveVirtualBullets(), run().
    private final List<VBullet> vbullets = new ArrayList<>(); // virtual bullets waiting to be scored

    // guess factor buffers (see segments below) and how fast each one forgets
    private final double[][][] gf = { // separate guess factor history buffers for different enemy situations
            new double[16][BINS],   // 0: flight time x sideways speed
            new double[36][BINS],   // 1: sideways speed x wall room x acceleration
            new double[36][BINS],   // 2: flight time x advancing speed x time since change
            new double[1][BINS]     // 3: everything together, forgets fast
    };
    // Referenced in: resolveVirtualBullets().
    private static final double[] GF_DECAY = {0.995, 0.99, 0.993, 0.97}; // how much old data each buffer keeps each time it learns
    // Referenced in: combinedStats().
    private static final double[] GF_WEIGHT = {1.0, 1.0, 1.0, 0.5};      // how strongly each buffer affects the final aim prediction

    private static class VBullet { // a pretend bullet for each gun
        // Referenced in: onScannedBot() when the virtual bullet is created, and resolveVirtualBullets() when it is scored.
        double ox, oy, speed; // where it started and how fast it goes
        // Referenced in: onScannedBot() and resolveVirtualBullets().
        int fireTurn; // turn it was fired
        // Referenced in: onScannedBot() when the four aim methods are saved, and resolveVirtualBullets() when they are compared.
        double[] angles = new double[GUNS]; // where each gun aimed
        // Referenced in: onScannedBot() and resolveVirtualBullets().
        double abs; // direct angle to the enemy at fire time
        // Referenced in: onScannedBot() and resolveVirtualBullets().
        int latDir; // enemy sideways direction at fire time
        // Referenced in: onScannedBot() and resolveVirtualBullets().
        double mea; // max escape angle for this bullet speed
        // Referenced in: onScannedBot() when the bullet is created, and resolveVirtualBullets() when learning is updated.
        int[] seg = new int[4]; // which segment of each buffer applied
    }

    public static void main(String[] args) { // program entry point
        new OPBot().start(); // make the bot and connect to the server
    }

    OPBot() { // constructor
        super(BotInfo.builder() // hand bot info to the base class
                .setName("OPBot") // bot name
                .setVersion("3.1.4.1.5.9.2.6.5.3.5") // bot version
                .addAuthor("me") // bot author
                .build()); // finish building the info
    }

    @Override
    // Called by Tank Royale the start of each round.
    public void run() { // called at the start of every round
        setAdjustGunForBodyTurn(true); // gun turns independent of the body
        setAdjustRadarForGunTurn(true); // radar turns independent of the gun
        setAdjustRadarForBodyTurn(true); // radar turns independent of the body

        setBodyColor(Color.fromRgb(0, 0, 0)); // black body
        setBulletColor(Color.fromRgb(0, 0, 0)); // black bullets
        setTurretColor(Color.fromRgb(0, 0, 0)); // black turret
        setScanColor(Color.fromRgb(28, 101, 27)); // green scan arc
        setRadarColor(Color.fromRgb(28, 101, 27)); // green radar

        // reset per round stuff, gunScore gf and surf stay so we keep learning
        targetId = -1; // no target yet
        lastScanTurn = -100; // pretend we scanned long ago
        scanTurn = -100; // same for the newest scan
        prevScanTurn = -100; // and the one before
        orbitDir = Math.random() < 0.5 ? 1 : -1; // random starting orbit direction
        flipCooldown = 0; // allow flipping right away
        dmgDealt = 0; // clear damage bookkeeping
        gainFromHit = 0; // clear energy gain bookkeeping
        lastCollisionTurn = -100; // no recent collision
        sweepUntil = -1; // no sweep in progress
        lastSweepEnd = 0; // reset sweep timer
        destTurn = -100; // force a new melee destination
        bots.clear(); // forget all known bots
        vbullets.clear(); // drop virtual bullets
        eWaves.clear(); // drop enemy waves
        setMaxSpeed(8); // full speed

        while (isRunning()) { // main loop, one pass per tick
            if (flipCooldown > 0) flipCooldown--; // tick down the flip cooldown

            doRadar(); // point the radar
            if (getTurnNumber() - lastScanTurn > 20) { // havent seen the target in a while
                targetId = -1;   // target went stale
            }

            processEnemyShots(); // check if the enemy fired
            updateWaves(); // move and clean up waves
            doMovement(); // decide where to drive
            go(); // send all the commands for this tick
        }
    }

    // Called by: run().
    private void doRadar() { // radar control each tick
        int now = getTurnNumber(); // current turn
        boolean lost = now - lastScanTurn > 2; // target slipped out of the beam
        if (getEnemyCount() > 1) { // melee
            if (now < sweepUntil || targetId == -1 || lost) { // sweeping or no good lock
                setTurnRadarLeft(Double.POSITIVE_INFINITY); // spin the radar
            } else if (now - lastSweepEnd > 25) { // been a while since a full sweep
                sweepUntil = now + 9;          // one full turn takes 8 ticks
                lastSweepEnd = now + 9; // remember when this sweep ends
                setTurnRadarLeft(Double.POSITIVE_INFINITY); // start spinning
            }
        } else if (lost) { // 1v1 and we lost the enemy
            setTurnRadarLeft(Double.POSITIVE_INFINITY); // spin to find them again
        }
    }

    // runs once a tick after the events, did the target just fire, if so start a wave
    // Called by: run().
    private void processEnemyShots() { // compare energy between scans to tell whether the enemy fired
        int now = getTurnNumber(); // current turn
        if (targetId == -1 || scanTurn != now) return; // need a fresh scan of the target

        double expected = prevEnergy - dmgDealt + gainFromHit; // energy we expect after known hits
        double drop = expected - tEnergy; // unexplained drop, probably a shot
        dmgDealt = 0; // reset for next tick
        gainFromHit = 0; // reset for next tick

        boolean collided = now - lastCollisionTurn <= 1; // ramming also costs energy
        if (drop >= 0.09 && drop <= 3.01 && !collided && scanTurn - prevScanTurn <= 2) { // looks like a real shot
            if (getEnemyCount() == 1) { // 1v1
                spawnWave(drop); // track the bullet as a wave
            } else if (Math.random() < 0.8) { // melee
                flipDirection(false); // just dodge by changing direction
            }
        }

        prevEnergy = tEnergy; // save energy for next time
        prevScanTurn = scanTurn; // save scan turn for next time
        pTx = tx; // save target x
        pTy = ty; // save target y
        pMeX = getX(); // save our x
        pMeY = getY(); // save our y
        myLatDirPrev = myLatDir; // shift our sideways direction history
        double lat = getSpeed() * Math.sin(Math.toRadians(getDirection() - angleTo(tx, ty, getX(), getY()))); // our sideways speed vs the enemy
        if (Math.abs(lat) > 0.1) myLatDir = lat > 0 ? 1 : -1; // only update when were actually moving sideways
    }

    // Called by: processEnemyShots().
    private void spawnWave(double power) { // make a wave from a detected shot
        EWave w = new EWave(); // new wave
        w.ox = tx; // starts at the enemy x
        w.oy = ty; // starts at the enemy y
        w.speed = 20 - 3 * power; // bullet speed formula
        w.fireTurn = scanTurn + WAVE_OFFSET; // apply the timing fix
        w.directAngle = angleTo(w.ox, w.oy, pMeX, pMeY); // angle straight at where we were
        w.latDir = myLatDirPrev; // our sideways direction at fire time
        w.mea = Math.toDegrees(Math.asin(8.0 / w.speed)); // max escape angle
        eWaves.add(w); // start tracking it
    }

    // Called by: matchWave(), predictDanger(), timeLeft(), updateWaves().
    private double waveRadius(EWave w, int turn) { // how far the wave front has traveled
        return w.speed * (turn - w.fireTurn); // speed times age
    }

    // drop waves that already passed us, learn a little from the ones that missed
    // Called by: run().
    private void updateWaves() { // move enemy bullet waves forward and remove the ones that passed
        int now = getTurnNumber(); // current turn
        Iterator<EWave> it = eWaves.iterator(); // iterator so we can remove while looping
        while (it.hasNext()) { // go through every wave
            EWave w = it.next(); // next wave
            double d = Math.hypot(getX() - w.ox, getY() - w.oy); // distance from wave origin to us
            if (waveRadius(w, now) > d + BOT_HALF + 4) { // wave front fully past us
                if (PASS_WEIGHT > 0) recordSurf(w, getX(), getY(), PASS_WEIGHT); // note that this spot was safe-ish
                it.remove(); // done with this wave
            }
        }
    }

    // Called by: predictDanger(), recordSurf().
    private double gfOf(EWave w, double px, double py) { // guess factor of a point relative to a wave
        double offset = norm(angleTo(w.ox, w.oy, px, py) - w.directAngle); // angle offset from direct
        return clamp(offset * w.latDir / w.mea, -1, 1); // scale to -1..1 and fix the direction
    }

    // Called by: onHitByBullet(), updateWaves().
    private void recordSurf(EWave w, double px, double py, double weight) { // add a hit or pass to surf stats
        int idx = (int) Math.round(gfOf(w, px, py) * CENTER + CENTER); // bin where it happened
        for (int b = 0; b < BINS; b++) { // spread over nearby bins
            double z = (b - idx) / 1.6; // distance from the center bin
            surf[b] += weight * Math.exp(-0.5 * z * z); // gaussian bump
        }
    }

    // Called by: run().
    private void doMovement() { // pick the movement style
        if (targetId == -1) { // nobody to fight yet
            // no target yet so cruise in a wide arc while the radar finds someone
            double travel = smooth(getX(), getY(), getDirection() + 15 * orbitDir, orbitDir); // gentle curve staying in bounds
            driveToward(travel, 150); // drive along it
        } else if (getEnemyCount() == 1) { // 1v1
            surfMove(); // wave surf
        } else { // melee
            meleeMove(); // minimum risk
        }
    }

    // 1v1 wave surfing

    // Called by: doMovement().
    private void surfMove() { // 1v1 movement
        double pref = prefDist; // base preferred distance
        if (getEnergy() < tEnergy - 30) pref += 100; // back off when were way behind on energy

        int dir = orbitDir; // start with the current direction
        List<EWave> ws = upcomingWaves(2); // the next two waves
        if (!ws.isEmpty()) { // something incoming
            double dPlus = predictDanger(ws, 1, pref); // danger going counter clockwise
            double dMinus = predictDanger(ws, -1, pref); // danger going clockwise
            if (dPlus < dMinus - 1e-9) dir = 1; // counter clockwise is safer
            else if (dMinus < dPlus - 1e-9) dir = -1; // clockwise is safer
            orbitDir = dir; // commit to it
        } else if (flipCooldown == 0 && Math.random() < 0.03) { // nothing incoming, small random chance
            flipDirection(false);   // nothing incoming but still switch it up now and then
            dir = orbitDir; // use the new direction
        }

        double travel = orbitAngle(getX(), getY(), tx, ty, dir, pref); // heading to circle the enemy
        travel = smooth(getX(), getY(), travel, dir); // keep it inside the arena
        driveToward(travel, 120); // go
    }

    // the (up to n) waves still on their way, soonest first
    // Called by: surfMove().
    private List<EWave> upcomingWaves(int n) { // return the closest waves that will reach us next
        int now = getTurnNumber(); // current turn
        List<EWave> out = new ArrayList<>(eWaves); // copy so we can sort
        out.sort((a, b) -> Double.compare(timeLeft(a, now), timeLeft(b, now))); // soonest first
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out; // trim to n
    }

    // Called by: upcomingWaves().
    private double timeLeft(EWave w, int now) { // ticks until a wave reaches us
        double d = Math.hypot(getX() - w.ox, getY() - w.oy); // distance to wave origin
        return (d - waveRadius(w, now)) / w.speed; // gap left divided by speed
    }

    // direction to drive to circle the enemy in direction dir, nudged toward or away from the preferred distance
    // Called by: predictDanger(), surfMove().
    private double orbitAngle(double x, double y, double ex, double ey, int dir, double pref) { // calculate the direction needed to circle the enemy
        double theta = angleTo(ex, ey, x, y);      // from the enemy to us
        double d = Math.hypot(x - ex, y - ey); // current distance
        double k = clamp((d - pref) / 5.0, -45, 45);   // too far means lean toward the enemy
        return theta + dir * (90 + k); // perpendicular plus the lean
    }

    // pretend we keep orbiting in direction dir with the real turn and accel limits
    // and see where we end up when each wave hits
    // danger is how often weve been hit at that spot before
    // Called by: surfMove().
    private double predictDanger(List<EWave> ws, int dir, double pref) { // simulate a movement direction and total up the danger
        int now = getTurnNumber(); // current turn
        EWave w0 = ws.get(0); // orbit around the first waves origin
        double px = getX(), py = getY(), ph = getDirection(), pv = getSpeed(); // simulated position heading and velocity
        boolean[] done = new boolean[ws.size()]; // which waves have reached us in the sim
        int remaining = ws.size(); // waves still to reach us
        double total = 0; // accumulated danger
        double lo = BOT_HALF, hiX = getArenaWidth() - BOT_HALF, hiY = getArenaHeight() - BOT_HALF; // arena limits for our center

        for (int t = 1; t <= 160 && remaining > 0; t++) { // step the sim forward tick by tick
            double want = smooth(px, py, orbitAngle(px, py, w0.ox, w0.oy, dir, pref), dir); // where wed like to head
            double turn = norm(want - ph); // turn needed
            int md = 1; // move direction, 1 forward
            if (Math.abs(turn) > 90) { // quicker to reverse
                turn = norm(turn - 180); // turn the other way
                md = -1; // drive backward
            }
            double maxT = 10 - 0.75 * Math.abs(pv); // max turn rate depends on speed
            ph = norm(ph + clamp(turn, -maxT, maxT)); // apply the turn
            pv += (pv * md < 0) ? 2 * md : md; // braking is faster than speeding up
            pv = clamp(pv, -8, 8); // speed limit
            px = clamp(px + Math.cos(Math.toRadians(ph)) * pv, lo, hiX); // move x
            py = clamp(py + Math.sin(Math.toRadians(ph)) * pv, lo, hiY); // move y

            for (int i = 0; i < ws.size(); i++) { // check each wave
                if (done[i]) continue; // already handled
                EWave w = ws.get(i); // this wave
                if (Math.hypot(px - w.ox, py - w.oy) <= waveRadius(w, now + t)) { // wave has reached the simulated spot
                    done[i] = true; // mark it
                    remaining--; // one less to go
                    int idx = (int) Math.round(gfOf(w, px, py) * CENTER + CENTER); // bin wed be in
                    total += surf[idx] * (i == 0 ? 1.0 : 0.5); // add danger, later waves count less
                }
            }
        }
        return total; // lower is safer
    }

    // melee minimum risk

    // Called by: doMovement().
    private void meleeMove() { // melee movement
        double x = getX(), y = getY(); // our position
        int now = getTurnNumber(); // current turn
        if (now - destTurn > 14 || Math.hypot(destX - x, destY - y) < 25) { // destination old or reached
            pickDestination(); // choose a new one
        }
        double d = Math.hypot(destX - x, destY - y); // distance to destination
        driveToward(angleTo(x, y, destX, destY), Math.max(20, Math.min(d, 150))); // head there
    }

    // Called by: meleeMove().
    private void pickDestination() { // sample points and take the safest
        double x = getX(), y = getY(); // our position
        double best = Double.MAX_VALUE; // lowest risk so far
        double bx = x, by = y; // best point, default to staying put
        double margin = 35; // stay this far from the walls
        for (int i = 0; i < 36; i++) { // 36 candidate points
            double ang = Math.toRadians(i * 10 + Math.random() * 10); // spread around us with some jitter
            double r = 70 + Math.random() * 110; // random distance
            double px = x + Math.cos(ang) * r; // candidate x
            double py = y + Math.sin(ang) * r; // candidate y
            if (px < margin || px > getArenaWidth() - margin || py < margin || py > getArenaHeight() - margin) continue; // skip points near walls
            double risk = riskAt(px, py, x, y); // score it
            if (risk < best) { // safer than the best so far
                best = risk; // new best risk
                bx = px; // new best x
                by = py; // new best y
            }
        }
        destX = bx; // set destination x
        destY = by; // set destination y
        destTurn = getTurnNumber(); // remember when we picked it
    }

    // Called by: pickDestination().
    private double riskAt(double px, double py, double x, double y) { // how dangerous is a point
        int now = getTurnNumber(); // current turn
        double risk = 0; // running total
        for (double[] b : bots.values()) { // every known bot
            if (now - b[2] > 40) continue;                      // old info
            double d2 = Math.max(400, (px - b[0]) * (px - b[0]) + (py - b[1]) * (py - b[1])); // squared distance with a floor
            double a1 = angleTo(b[0], b[1], x, y); // angle from them to where we are now
            double a2 = angleTo(b[0], b[1], px, py); // angle from them to the candidate
            double perp = 1 + Math.abs(Math.cos(Math.toRadians(a1 - a2)));  // moving toward them is worse than sideways
            double weight = 1 + b[3] / 50.0;                    // stronger enemies are scarier
            risk += weight * perp * 90000 / d2; // closer means way riskier
        }
        double edge = Math.min(Math.min(px, getArenaWidth() - px), Math.min(py, getArenaHeight() - py)); // distance to nearest wall
        if (edge < 120) risk += (120 - edge) / 120 * 1.5; // penalty for hugging walls
        double cx = Math.min(px, getArenaWidth() - px), cy = Math.min(py, getArenaHeight() - py); // distance to nearest wall on each axis
        if (cx < 150 && cy < 150) risk += 2;                    // corners are traps
        return risk + Math.random() * 0.05; // tiny noise to break ties
    }

    // shared driving helpers

    // turn the short way and drive forward or backward toward the travel direction
    // Called by: doMovement(), meleeMove(), surfMove().
    private void driveToward(double travel, double distance) { // turn toward the chosen angle and move that way
        double delta = norm(travel - getDirection()); // how far we need to turn
        int dir = 1; // forward by default
        if (Math.abs(delta) > 90) { // faster to go backward
            delta = norm(delta - 180); // flip the turn
            dir = -1; // reverse
        }
        setTurnLeft(delta); // queue the turn
        setForward(distance * dir); // queue the move
    }

    // rotate the travel direction toward the enemy side bit by bit until the path ahead stays in the arena
    // dir is the orbit direction so +dir turns toward the enemy
    // Called by: doMovement(), predictDanger(), surfMove().
    private double smooth(double x, double y, double angle, int dir) { // adjust a movement angle if its path would run into a wall
        for (int i = 0; i <= 60; i++) { // up to 180 degrees of adjustment
            double a = angle + dir * i * 3; // try turning 3 degrees more each time
            if (insideField(x, y, a)) return a; // first safe angle wins
        }
        return angle; // nothing worked, use the original
    }

    // Called by: smooth().
    private boolean insideField(double fromX, double fromY, double angle) { // would we still be in the arena
        double look = 140; // how far ahead we check
        double margin = 28; // wall buffer
        double x = fromX + Math.cos(Math.toRadians(angle)) * look; // look ahead x
        double y = fromY + Math.sin(Math.toRadians(angle)) * look; // look ahead y
        return x > margin && x < getArenaWidth() - margin // x inside the buffer
                && y > margin && y < getArenaHeight() - margin; // y inside the buffer
    }

    // Called by: onHitBot(), onHitByBullet(), onHitWall(), processEnemyShots(), surfMove().
    private void flipDirection(boolean force) { // reverse the orbit
        if (force || flipCooldown == 0) { // only when forced or cooled down
            orbitDir = -orbitDir; // swap direction
            flipCooldown = 10; // wait a bit before the next flip
            prefDist = 320 + Math.random() * 130;   // new preferred distance every time
        }
    }

    @Override
    // Called by Tank Royale Tank Royale detects another bot with the radar.
    public void onScannedBot(ScannedBotEvent e) { // radar saw a bot
        int id = e.getScannedBotId(); // who it is
        int now = getTurnNumber(); // current turn
        double dist = distanceTo(e.getX(), e.getY()); // how far away

        bots.put(id, new double[]{e.getX(), e.getY(), now, e.getEnergy()}); // update the bot table

        double currentTargetDist = (targetId == -1) ? Double.MAX_VALUE : distanceTo(tx, ty); // distance to our current target

        // pick the target, stick with it unless theres none, someone is way closer,
        // or someone is way weaker and not far off
        boolean switchTarget = (targetId == -1); // no target means we take this one
        if (!switchTarget && id != targetId) { // seeing someone else
            if (dist < currentTargetDist * 0.7) switchTarget = true; // much closer
            else if (e.getEnergy() < tEnergy * 0.5 && dist < currentTargetDist * 1.3) switchTarget = true; // much weaker and nearby
        }
        if (switchTarget) { // lock onto the new target
            targetId = id; // remember who
            tTurnRate = 0; // no turn data yet
            prevDir = e.getDirection(); // starting heading
            tx = pTx = e.getX(); // set current and previous x
            ty = pTy = e.getY(); // set current and previous y
            tDir = e.getDirection(); // heading
            tSpeed = e.getSpeed(); // speed
            tEnergy = prevEnergy = e.getEnergy(); // set current and previous energy
            lastScanTurn = now - 1; // pretend we saw them last tick
            prevScanTurn = now - 1; // same here
            lastChangeTurn = now; // fresh start for the change timer
            dmgDealt = 0; // clear bookkeeping
            gainFromHit = 0; // clear bookkeeping
            vbullets.clear();   // old virtual bullets were for the last target
            eWaves.clear(); // old waves too
        }
        if (id != targetId) return; // only the target matters from here

        // track enemy state
        int dt = Math.max(1, now - lastScanTurn); // ticks since last scan
        tTurnRate = norm(e.getDirection() - prevDir) / dt; // degrees turned per tick
        prevDir = e.getDirection(); // save heading
        double prevSpeed = tSpeed; // save speed for acceleration

        tx = e.getX(); // update x
        ty = e.getY(); // update y
        tDir = e.getDirection(); // update heading
        tSpeed = e.getSpeed(); // update speed
        tEnergy = e.getEnergy(); // update energy
        lastScanTurn = now; // mark scan time
        scanTurn = now; // mark newest scan

        // radar lock, always in 1v1, in melee except during a full sweep
        if (getEnemyCount() == 1 || now >= sweepUntil) { // lock allowed
            double neededTurn = radarBearingTo(tx, ty); // how far to turn the radar
            double angularWidth = Math.toDegrees(2 * Math.atan(BOT_HALF / Math.max(dist, 1))); // how wide the bot looks
            double overshoot = Math.copySign(angularWidth * 0.75 + 2, neededTurn); // swing past so the beam keeps catching them
            setTurnRadarLeft(neededTurn + overshoot); // aim the radar
        }

        // learn from virtual bullets that reached the enemy
        resolveVirtualBullets(); // check which virtual bullets have reached the target and update gun scores

        // fire power
        double power; // bullet power
        if (dist < 150) power = 3; // close, hit hard
        else if (dist < 350) power = 2.5; // medium
        else if (dist < 600) power = 1.8; // far
        else power = 1.2; // very far, save energy
        if (getEnemyCount() > 1 && dist > 500) power = Math.min(power, 1.5); // melee and far, go easy
        if (getEnergy() < 20) power = Math.min(power, 1.5); // low energy, be careful
        if (getEnergy() < 8) power = Math.min(power, 0.8); // really low, be very careful
        // dont use more power than a kill shot needs (damage is 4p, or 6p - 2 above power 1)
        double killPower = tEnergy <= 4 ? tEnergy / 4 : (tEnergy + 2) / 6; // power that exactly kills
        power = Math.min(power, Math.max(0.1, killPower + 0.05)); // cap at the kill shot plus a hair
        power = Math.max(0.1, Math.min(3, power)); // keep it in the legal range

        double bulletSpeed = calcBulletSpeed(power); // speed for that power

        // situation for the guess factor gun
        double abs = angleTo(getX(), getY(), tx, ty); // direct angle to the enemy
        double lv = tSpeed * Math.sin(Math.toRadians(tDir - abs));   // enemys sideways speed
        if (Math.abs(lv) > 0.5) { // moving sideways enough to count
            int s = lv > 0 ? 1 : -1; // which way
            if (s != lastLatDir) lastChangeTurn = now; // direction changed
            lastLatDir = s; // remember it
        }
        if (Math.abs(tSpeed - prevSpeed) > 1.5) lastChangeTurn = now; // big speed change
        int latDir = lastLatDir; // sideways direction to use
        int[] seg = segments(dist, bulletSpeed, lv, prevSpeed, now); // current situation buckets
        double mea = Math.toDegrees(Math.asin(8.0 / bulletSpeed));   // max escape angle

        // aim angle for each gun in absolute degrees
        double[] aim = new double[GUNS]; // one aim angle per gun
        aim[0] = abs;                                                        // head on
        double[] lin = predict(0, bulletSpeed); // predicted spot if they go straight
        aim[1] = angleTo(getX(), getY(), lin[0], lin[1]);                    // linear
        double[] cir = predict(tTurnRate, bulletSpeed); // predicted spot if they keep turning
        aim[2] = angleTo(getX(), getY(), cir[0], cir[1]);                    // circular

        double[] comb = combinedStats(seg); // combined votes from every buffer
        int bestBin = CENTER; // start at the middle
        for (int b = 0; b < BINS; b++) { // look for the tallest bin
            if (comb[b] > comb[bestBin] + 1e-9) bestBin = b; // new peak
        }
        double gfAim = (bestBin - CENTER) / (double) CENTER; // convert bin to guess factor
        aim[3] = abs + latDir * gfAim * mea;                                 // guess factor
        for (int g = 0; g < GUNS; g++) aim[g] = clampAim(aim[g], dist);      // never aim outside the arena

        // record a virtual bullet for every gun, scored later
        VBullet vb = new VBullet(); // new virtual bullet
        vb.ox = getX(); // our x at fire time
        vb.oy = getY(); // our y at fire time
        vb.speed = bulletSpeed; // its speed
        vb.fireTurn = now + 1; // it leaves next tick
        vb.abs = abs; // direct angle then
        vb.latDir = latDir; // enemy sideways direction then
        vb.mea = mea; // max escape angle
        System.arraycopy(seg, 0, vb.seg, 0, 4); // copy segment indexes
        System.arraycopy(aim, 0, vb.angles, 0, GUNS); // copy every guns aim
        vbullets.add(vb); // queue it for scoring
        if (vbullets.size() > 200) vbullets.remove(0); // dont let the list grow forever

        // use whichever gun has been hitting best, ties go to circular
        int best = 2; // default to circular
        for (int g = 0; g < GUNS; g++) { // compare all guns
            if (gunScore[g] > gunScore[best]) best = g; // better scorer
        }

        // aim and fire
        double gunTurn = norm(aim[best] - getGunDirection()); // how far to turn the gun
        setTurnGunLeft(gunTurn); // queue the gun turn
        double tolerance = Math.toDegrees(Math.atan(BOT_HALF / Math.max(dist, 1))); // angle that still hits the bot
        if (Math.abs(gunTurn) <= tolerance && getGunHeat() == 0 && getEnergy() > power) { // aimed, cool and have energy
            setFire(power); // shoot
        }
    }

    // which segment of each guess factor buffer matches the current situation
    // Called by: onScannedBot().
    private int[] segments(double dist, double bulletSpeed, double lv, double prevSpeed, int now) { // group the current fight into matching guess factor data sections
        double alv = Math.abs(lv); // absolute sideways speed
        double flight = dist / bulletSpeed; // bullet flight time
        int ft = flight < 10 ? 0 : (flight < 20 ? 1 : (flight < 30 ? 2 : 3)); // flight time bucket
        int lvb = alv < 1 ? 0 : (alv < 3 ? 1 : (alv < 5.5 ? 2 : 3)); // sideways speed bucket

        double abs = angleTo(getX(), getY(), tx, ty); // direct angle to the enemy
        double adv = -tSpeed * Math.cos(Math.toRadians(tDir - abs));   // positive means coming at us
        int advb = adv < -2 ? 0 : (adv < 2 ? 1 : 2); // advancing speed bucket

        double moveAngle = tSpeed >= 0 ? tDir : tDir + 180; // direction theyre actually moving
        double room = rayToWall(tx, ty, moveAngle) / (8 * flight + 1); // wall room compared to how far they can go
        int wallb = room < 0.6 ? 0 : (room < 1.2 ? 1 : 2); // wall room bucket

        double accel = Math.abs(tSpeed) - Math.abs(prevSpeed); // speeding up or slowing down
        int accb = accel < -0.5 ? 0 : (accel > 0.5 ? 2 : 1); // acceleration bucket

        int since = now - lastChangeTurn; // ticks since the last change
        int sinceb = since < 12 ? 0 : (since < 35 ? 1 : 2); // time since change bucket

        return new int[]{ // one index per buffer
                ft * 4 + lvb, // buffer 0
                (lvb * 3 + wallb) * 3 + accb, // buffer 1
                (ft * 3 + advb) * 3 + sinceb, // buffer 2
                0 // buffer 3 has just one segment
        };
    }

    // every buffer votes, a segment with little data has little say
    // Called by: onScannedBot().
    private double[] combinedStats(int[] seg) { // combine the predictions from all guess factor buffers
        double[] out = new double[BINS]; // combined result
        for (int k = 0; k < gf.length; k++) { // each buffer
            double[] st = gf[k][seg[k]]; // the segment that matches now
            double total = 1; // starts at 1 so empty segments stay quiet
            for (int b = 0; b < BINS; b++) total += st[b]; // sum the bins
            for (int b = 0; b < BINS; b++) out[b] += GF_WEIGHT[k] * st[b] / total; // add a normalized weighted vote
        }
        return out; // final votes
    }

    // distance from x y to the arena edge (inset by half a bot) along the given direction
    // Called by: segments().
    private double rayToWall(double x, double y, double angle) { // calculate how far the enemy can travel before reaching a wall
        double dx = Math.cos(Math.toRadians(angle)), dy = Math.sin(Math.toRadians(angle)); // direction vector
        double t = 1e9; // closest hit so far
        if (dx > 1e-9) t = Math.min(t, (getArenaWidth() - BOT_HALF - x) / dx); // heading right, hit the right wall
        else if (dx < -1e-9) t = Math.min(t, (BOT_HALF - x) / dx); // heading left, hit the left wall
        if (dy > 1e-9) t = Math.min(t, (getArenaHeight() - BOT_HALF - y) / dy); // heading up, hit the top wall
        else if (dy < -1e-9) t = Math.min(t, (BOT_HALF - y) / dy); // heading down, hit the bottom wall
        return Math.max(0, t); // never negative
    }

    // if an aim angle points outside the arena pull the aim point back in
    // Called by: onScannedBot().
    private double clampAim(double angle, double dist) { // keep the gun's aim point inside the arena
        double px = clamp(getX() + Math.cos(Math.toRadians(angle)) * dist, BOT_HALF, getArenaWidth() - BOT_HALF); // aim point x kept inside
        double py = clamp(getY() + Math.sin(Math.toRadians(angle)) * dist, BOT_HALF, getArenaHeight() - BOT_HALF); // aim point y kept inside
        return angleTo(getX(), getY(), px, py); // angle to the clamped point
    }

    // predict where the enemy will be when a bullet of this speed gets there
    // Called by: onScannedBot().
    private double[] predict(double turnRate, double bulletSpeed) { // estimate the enemy's future position when our bullet arrives
        double px = tx, py = ty, heading = tDir; // start at the enemys current state
        for (int t = 1; t <= 100; t++) { // step forward in time
            heading += turnRate; // apply the turn
            double r = Math.toRadians(heading); // heading in radians
            px += Math.cos(r) * tSpeed; // move x
            py += Math.sin(r) * tSpeed; // move y
            px = clamp(px, BOT_HALF, getArenaWidth() - BOT_HALF); // stay in the arena x
            py = clamp(py, BOT_HALF, getArenaHeight() - BOT_HALF); // stay in the arena y
            if (distanceTo(px, py) <= bulletSpeed * t) break; // bullet would have arrived by now
        }
        return new double[]{px, py}; // predicted position
    }

    // a virtual bullet arrives once its traveled as far as the enemy is from its origin
    // then we score each gun on whether it couldve hit
    // and record where the enemy really was in guess factor terms
    // Called by: onScannedBot().
    private void resolveVirtualBullets() { // score the aiming methods after their pretend bullets reach the target
        Iterator<VBullet> it = vbullets.iterator(); // iterator so we can remove while looping
        while (it.hasNext()) { // every virtual bullet
            VBullet vb = it.next(); // this one
            int age = getTurnNumber() - vb.fireTurn; // ticks since it was fired
            if (age > 120) { // too old to matter
                it.remove(); // toss it
                continue; // next one
            }
            double d = Math.hypot(tx - vb.ox, ty - vb.oy); // distance from origin to the enemy now
            if (vb.speed * age >= d) { // bullet has reached the enemy
                double actual = angleTo(vb.ox, vb.oy, tx, ty); // where the enemy really is
                double tol = Math.toDegrees(Math.atan(BOT_HALF / Math.max(d, 1))); // hit tolerance at this range

                // score the guns
                for (int g = 0; g < GUNS; g++) { // each gun
                    boolean hit = Math.abs(norm(actual - vb.angles[g])) <= tol; // would it have hit
                    gunScore[g] = gunScore[g] * 0.97 + (hit ? 1 : 0); // decayed running score
                }

                // update every guess factor buffer, smoothed around the observed bin
                double offset = norm(actual - vb.abs); // offset from the direct angle
                double g = clamp(offset * vb.latDir / vb.mea, -1, 1); // guess factor that happened
                int idx = (int) Math.round(g * CENTER + CENTER); // its bin
                for (int k = 0; k < gf.length; k++) { // each buffer
                    double[] st = gf[k][vb.seg[k]]; // the segment that applied at fire time
                    for (int b = 0; b < BINS; b++) { // each bin
                        double z = (b - idx) / 1.5; // distance from the observed bin
                        st[b] = st[b] * GF_DECAY[k] + Math.exp(-0.5 * z * z); // forget a bit then add a gaussian bump
                    }
                }

                it.remove(); // finished with this bullet
            }
        }
    }

    @Override
    // Called by Tank Royale Tank Royale reports a bot death.
    public void onBotDeath(BotDeathEvent e) { // someone died
        bots.remove(e.getVictimId()); // forget them
        if (e.getVictimId() == targetId) { // it was our target
            targetId = -1; // need a new one
            vbullets.clear(); // their virtual bullets are useless now
            eWaves.clear(); // and their waves
        }
    }

    @Override
    // Called by Tank Royale one of our bullets hits a bot.
    public void onBulletHit(BulletHitBotEvent e) { // our bullet hit someone
        if (e.getVictimId() == targetId) dmgDealt += e.getDamage(); // count it so we dont mistake it for a shot
    }

    @Override
    // Called by Tank Royale two bullets collide.
    public void onBulletHitBullet(BulletHitBulletEvent e) { // two bullets collided
        // one of our bullets cancelled an enemy bullet so forget that wave
        EWave w = matchWave(e.getHitBullet()); // find the matching wave
        if (w != null) eWaves.remove(w); // drop it
    }

    @Override
    // Called by Tank Royale our bot collides with another bot.
    public void onHitBot(HitBotEvent e) { // we rammed a bot
        lastCollisionTurn = getTurnNumber(); // note it so the energy drop isnt read as a shot
        flipDirection(true);   // collision so back off instead of trading ram damage
    }

    @Override
    // Called by Tank Royale our bot hits a wall.
    public void onHitWall(HitWallEvent e) { // we hit a wall
        flipDirection(true); // turn around
    }

    @Override
    // Called by Tank Royale an enemy bullet hits our bot.
    public void onHitByBullet(HitByBulletEvent e) { // we got shot
        BulletState b = e.getBullet(); // the bullet
        gainFromHit += 3 * b.getPower();      // shooter gets energy back when it hits
        if (getEnemyCount() == 1) { // 1v1
            EWave w = matchWave(b); // which wave was it
            if (w != null) { // found it
                recordSurf(w, getX(), getY(), 1.0); // full weight, this spot was dangerous
                eWaves.remove(w); // done with the wave
            }
        } else { // melee
            flipDirection(false); // just change direction
        }
    }

    // which enemy wave does this bullet belong to, same speed and sitting where the waves edge is
    // Called by: onBulletHitBullet(), onHitByBullet().
    private EWave matchWave(BulletState b) { // find which tracked enemy wave matches the bullet that hit us
        int now = getTurnNumber(); // current turn
        EWave best = null; // best match so far
        double bestErr = 60; // max error we accept
        for (EWave w : eWaves) { // every wave
            if (Math.abs(w.speed - b.getSpeed()) > 0.3) continue; // speed doesnt match
            double err = Math.abs(Math.hypot(b.getX() - w.ox, b.getY() - w.oy) - waveRadius(w, now)); // how far the bullet is from the wave edge
            if (err < bestErr) { // closer than before
                bestErr = err; // new best error
                best = w; // new best wave
            }
        }
        return best; // null if nothing matched
    }

    @Override
    // Called by Tank Royale the connection to the server fails.
    public void onConnectionError(ConnectionErrorEvent e) { // couldnt reach the server
        System.out.println("Could not connect to Robocode Tank Royale."); // tell the user
        System.out.println("Open the Tank Royale app and press Ctrl+B (Battle -> Start Battle) FIRST,"); // how to fix it
        System.out.println("then click Run again."); // last instruction
        System.exit(1); // quit
    }

    // absolute angle (degrees, 0 is east) from x1 y1 to x2 y2
    // Called by: clampAim(), gfOf(), meleeMove(), onScannedBot(), orbitAngle(), processEnemyShots(), resolveVirtualBullets(), riskAt(), segments(), spawnWave().
    private static double angleTo(double x1, double y1, double x2, double y2) { // find the angle from one position to another
        return Math.toDegrees(Math.atan2(y2 - y1, x2 - x1)); // atan2 gives the angle, convert to degrees
    }

    // normalizes an angle to -180..180
    // Called by: driveToward(), gfOf(), onScannedBot(), predictDanger(), resolveVirtualBullets().
    private static double norm(double angle) { // keep an angle between -180 and 180 degrees
        while (angle > 180) angle -= 360; // too big, wrap down
        while (angle <= -180) angle += 360; // too small, wrap up
        return angle; // now in range
    }

    // Called by: clampAim(), gfOf(), orbitAngle(), predict(), predictDanger(), resolveVirtualBullets().
    private static double clamp(double v, double lo, double hi) { // keep a value between lo and hi
        return Math.max(lo, Math.min(hi, v)); // cap both ends
    }
}
