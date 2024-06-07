package org.example;

import javax.sound.sampled.*;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import org.glassfish.tyrus.server.Server;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.concurrent.*;

@ServerEndpoint("/soundboard")
public class Soundboard {
    private static Clip goalClip;
    private static Clip alternateGoalClip;
    private static Clip songClip;
    private static Clip thirdSongClip;
    private static Clip continuousClip;
    private static boolean goalClipFadingOut = false;
    private static boolean alternateGoalClipFadingOut = false;
    private static boolean songClipFadingOut = false;
    private static boolean thirdSongClipFadingOut = false;
    private static boolean continuousClipFadingOut = false;
    private static final CountDownLatch latch = new CountDownLatch(1);
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static boolean continuousClipPlaying = false;
    private static String basePath;
    private static final ConcurrentHashMap<Clip, Future<?>> fadeOutTasks = new ConcurrentHashMap<>();

    static {
        try {
            basePath = Paths.get(Soundboard.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().toString();
            goalClip = loadClip("GOAL(Long).wav");
            alternateGoalClip = loadClip("EGoal.wav");
            songClip = loadClip("Song.wav");
            thirdSongClip = loadClip("EGoalSong.wav");
            continuousClip = loadClip("Crowd.wav");
        } catch (URISyntaxException | IOException | UnsupportedAudioFileException | LineUnavailableException e) {
            e.printStackTrace();
        }
    }

    private static Clip loadClip(String fileName) throws IOException, UnsupportedAudioFileException, LineUnavailableException {
        File audioFile = new File(basePath, fileName);
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(audioFile);
        Clip clip = AudioSystem.getClip();
        clip.open(audioInputStream);
        return clip;
    }

    private static void setVolumeToMax(Clip clip) {
        FloatControl volumeControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        volumeControl.setValue(volumeControl.getMaximum());
    }

    public static void main(String[] args) {
        Server server = new Server("localhost", 8080, "/", null, Soundboard.class);
        try {
            server.start();
            System.out.println("Soundboard WebSocket server started.");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                    latch.countDown();
                    executorService.shutdown();
                    scheduler.shutdown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
            latch.await(); // Keep the main thread alive
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        System.out.println("Connection opened: " + session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println(message);
        switch (message) {
            case "pressed":
                if (!continuousClipPlaying && (songClip == null || !songClip.isRunning())) {
                    playContinuousClip();
                }
                playClip(goalClip);
                break;
            case "released":
                fadeOutSound(goalClip);
                fadeOutSound(alternateGoalClip);
                break;
            case "main":
                playClip(songClip);
                scheduler.schedule(() -> fadeOutSound(continuousClip), 5, TimeUnit.SECONDS);
                break;
            case "alternate":
                playClip(songClip);
                scheduler.schedule(() -> fadeOutSound(continuousClip), 5, TimeUnit.SECONDS);
                break;
            case "EGoal":
                playClip(alternateGoalClip);
                break;
            case "EGoalSong":
                playClip(thirdSongClip);
                break;
            case "all_stop":
                stopAllSounds();
                break;
            default:
                System.out.println("Unknown command: " + message);
        }
    }

    @OnClose
    public void onClose(Session session) {
        System.out.println("Connection closed: " + session.getId());
    }

    private static void playClip(Clip clip) {
        executorService.submit(() -> {

            cancelFadeOut(clip);
            setVolumeToMax(clip);
            clip.setFramePosition(0);
            clip.start();
        });
    }

    private static void playContinuousClip() {
        executorService.submit(() -> {
            if (!continuousClipPlaying) {
                continuousClip.setFramePosition(0);
                setVolumeToMax(continuousClip);
                continuousClip.start();
                continuousClipPlaying = true;
            }
        });
    }

    private static void fadeOutSound(Clip clip) {
        if (clip != null && clip.isRunning()) {
            cancelFadeOut(clip);
            Future<?> fadeOutTask = executorService.submit(() -> {
                if (clip == goalClip) goalClipFadingOut = true;
                if (clip == alternateGoalClip) alternateGoalClipFadingOut = true;
                if (clip == songClip) songClipFadingOut = true;
                if (clip == thirdSongClip) thirdSongClipFadingOut = true;
                if (clip == continuousClip) continuousClipFadingOut = true;

                FloatControl volume = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float initialVolume = volume.getValue();
                float minVolume = volume.getMinimum();
                int steps = 400; // More steps for a smoother fade-out
                float stepSize = (initialVolume - minVolume) / steps;

                for (int i = 0; i <= steps; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        setVolumeToMax(clip);
                        return;
                    }
                    float newVolume = initialVolume - stepSize * i;
                    volume.setValue(Math.max(newVolume, minVolume));
                    try {
                        Thread.sleep(5); // Adjust the sleep duration for a smoother transition
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                clip.stop();
                setVolumeToMax(clip);
                resetClip(clip);

                if (clip == continuousClip) continuousClipPlaying = false;

                if (clip == goalClip) goalClipFadingOut = false;
                if (clip == alternateGoalClip) alternateGoalClipFadingOut = false;
                if (clip == songClip) songClipFadingOut = false;
                if (clip == thirdSongClip) thirdSongClipFadingOut = false;
                if (clip == continuousClip) continuousClipFadingOut = false;
            });

            fadeOutTasks.put(clip, fadeOutTask);
        }
    }

    private static void cancelFadeOut(Clip clip) {
        Future<?> fadeOutTask = fadeOutTasks.get(clip);
        if (fadeOutTask != null && !fadeOutTask.isDone()) {
            fadeOutTask.cancel(true);
        }
        fadeOutTasks.remove(clip);
    }

    private static void resetClip(Clip clip) {
        clip.setFramePosition(0);
    }

    private static void stopAllSounds() {
        fadeOutSound(continuousClip);
        fadeOutSound(goalClip);
        fadeOutSound(alternateGoalClip);
        fadeOutSound(songClip);
        fadeOutSound(thirdSongClip);
        continuousClipPlaying = false;
    }
}
