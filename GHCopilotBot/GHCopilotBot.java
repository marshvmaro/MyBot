import dev.robocode.tankroyale.bot.Bot;
import dev.robocode.tankroyale.bot.BotInfo;
import dev.robocode.tankroyale.bot.events.BulletHitBotEvent;
import dev.robocode.tankroyale.bot.events.HitByBulletEvent;
import dev.robocode.tankroyale.bot.events.ScannedBotEvent;

import java.awt.Color;

/**
 * GHCopilotBot - a compact, competitive Robocode Tank Royale bot.
 *
 * It combines wall awareness, a predictive gun, and adaptive movement.
 */
public class GHCopilotBot extends Bot {

    private double enemyDistance = 500;
    private double enemyBearing = 0;
    private double enemySpeed = 0;
    private long enemyScanTime;
    private boolean hitWallRecently;

    public GHCopilotBot() {
        super(BotInfo.fromResource("GHCopilotBot.json"));
    }

    public static void main(String[] args) {
        new GHCopilotBot().start();
    }

    @Override
    public void run() {
        setBodyColor(Color.CYAN);
        setTurretColor(Color.BLUE);
        setRadarColor(Color.WHITE);
        setBulletColor(Color.ORANGE);

        setAdjustGunForBodyTurn(true);
        setAdjustRadarForGunTurn(true);

        while (isRunning()) {
            // Keep scanning when no enemy is visible.
            setTurnRadarRight(360);
            go();
        }
    }

    @Override
    public void onScannedBot(ScannedBotEvent event) {
        enemyScanTime = getTurnNumber();
        enemyDistance = event.getDistance();
        enemyBearing = event.getBearing();

        double enemyEnergyBefore = enemySpeed;
        enemySpeed = event.getSpeed();

        // Predictive target lead. The bullet speed is approximately 20 - 3 * firepower.
        double firePower = chooseFirePower(enemyDistance, event.getEnergy());
        double bulletSpeed = 20.0 - 3.0 * firePower;
        double absoluteBearing = normalizeDegrees(getDirection() + enemyBearing);

        double enemyCourse = normalizeDegrees(event.getDirection());
        double enemyVelocityX = Math.cos(Math.toRadians(enemyCourse)) * enemySpeed;
        double enemyVelocityY = Math.sin(Math.toRadians(enemyCourse)) * enemySpeed;

        double predictionX = event.getX() + enemyVelocityX
                * (enemyDistance / bulletSpeed);
        double predictionY = event.getY() + enemyVelocityY
                * (enemyDistance / bulletSpeed);

        double targetBearing = Math.toDegrees(Math.atan2(predictionX - getX(),
                predictionY - getY()));
        setTurnGunRight(normalizeDegrees(targetBearing - getGunDirection()));

        if (Math.abs(getGunTurnRemaining()) < 3.0
                && getGunHeat() == 0
                && enemyScanTime == getTurnNumber()) {
            setFire(firePower);
        }

        // Oscillating strafe movement keeps the bot difficult to hit.
        double moveDirection = 1.0;
        if (Math.abs(enemyBearing) > 90) {
            moveDirection = -1.0;
        }

        double movement = 100 + (enemyDistance * 0.15);
        double desiredDirection = absoluteBearing + (moveDirection > 0 ? 90 : -90);
        setTurnRight(normalizeDegrees(desiredDirection - getDirection()));

        if (enemyDistance < 180) {
            setMaxSpeed(4);
        } else if (enemyDistance > 550) {
            setMaxSpeed(8);
        } else {
            setMaxSpeed(6);
        }

        setForward(moveDirection * movement);

        // Aim the radar back toward the enemy for repeated tracking.
        setTurnRadarRight(normalizeDegrees(absoluteBearing - getRadarDirection()));

        go();
    }

    @Override
    public void onHitByBullet(HitByBulletEvent event) {
        // A short reverse and turn response avoids repeated shots.
        setForward(-70);
        setTurnRight(event.getBearing() + 135);
        hitWallRecently = false;
        go();
    }

    @Override
    public void onHitWall(dev.robocode.tankroyale.bot.events.HitWallEvent event) {
        hitWallRecently = true;
        setForward(-120);
        setTurnRight(90);
        go();
    }

    @Override
    public void onBulletHitBot(BulletHitBotEvent event) {
        // Repeated hits can be finished quickly with a stronger shot.
        setFire(Math.min(3.0, getEnergy() * 0.08));
        go();
    }

    private double chooseFirePower(double distance, double enemyEnergy) {
        if (getEnergy() < 20) {
            return 1.0;
        }
        if (enemyEnergy < 18 || distance < 180) {
            return 3.0;
        }
        if (distance < 350) {
            return 2.2;
        }
        return 1.0;
    }

    private double normalizeDegrees(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
