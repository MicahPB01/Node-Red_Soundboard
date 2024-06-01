package org.example;

import javax.sound.sampled.*;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import org.glassfish.tyrus.server.Server;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ServerEndpoint("/soundboard")
public class Soundboard {
    private static Clip goalClip;
    private static Clip songClip;
    private static Clip continuousClip;
    private static boolean isFadingOut = false;
    private static final CountDownLatch latch = new CountDownLatch(1);
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static boolean continuousClipPlaying = false;
    private static String basePath;

    static {
        try {
            basePath = Paths.get(Soundboard.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().toString();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
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
        switch (message) {
            case "pressed":
                if (!continuousClipPlaying && (songClip == null || !songClip.isRunning())) {
                    playContinuousSound("Crowd.wav");
                }
                playGoalSound("GOAL(Long).wav");
                break;
            case "released":
                fadeOutSound(goalClip);
                break;
            case "main":
                playSong("Song.wav");
                scheduler.schedule(() -> fadeOutSound(continuousClip), 5, TimeUnit.SECONDS);
                break;
            case "alternate":
                playSong("Song(Alternate).wav");
                scheduler.schedule(() -> fadeOutSound(continuousClip), 5, TimeUnit.SECONDS);
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

    private static void playGoalSound(String fileName) {
        executorService.submit(() -> playSound(fileName, true));
    }

    private static void playSong(String fileName) {
        executorService.submit(() -> playSound(fileName, false));
    }

    private static void playSound(String fileName, boolean isGoalSound) {
        try {
            File audioFile = new File(basePath, fileName);
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(audioFile);
            Clip clip = AudioSystem.getClip();
            clip.open(audioInputStream);
            clip.start();
            if (isGoalSound) {
                if (goalClip != null && goalClip.isRunning()) {
                    goalClip.stop();
                    goalClip.close();
                }
                goalClip = clip;
            } else {
                if (songClip != null && songClip.isRunning()) {
                    songClip.stop();
                    songClip.close();
                }
                songClip = clip;
            }
            isFadingOut = false;
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            e.printStackTrace();
        }
    }

    private static void playContinuousSound(String fileName) {
        executorService.submit(() -> {
            try {
                File audioFile = new File(basePath, fileName);
                AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(audioFile);
                continuousClip = AudioSystem.getClip();
                continuousClip.open(audioInputStream);
                continuousClip.start();
                continuousClipPlaying = true;
            } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
                e.printStackTrace();
            }
        });
    }

    private static void fadeOutSound(Clip clip) {
        if (clip != null && clip.isRunning() && !isFadingOut) {
            isFadingOut = true;
            new Thread(() -> {
                FloatControl volume = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float initialVolume = volume.getValue();
                int steps = 200; // Increase the number of steps for a smoother fade-out
                float stepSize = (initialVolume - volume.getMinimum()) / steps;

                for (int i = 0; i <= steps; i++) {
                    float newVolume = initialVolume - stepSize * i;
                    volume.setValue(newVolume);
                    try {
                        Thread.sleep(10); // Shorter sleep duration for smoother transition
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                clip.stop();
                clip.close();
                if (clip == continuousClip) {
                    continuousClipPlaying = false;
                }
                isFadingOut = false;
            }).start();
        }
    }


    private static void stopAllSounds() {
        fadeOutSound(goalClip);
        fadeOutSound(songClip);
        fadeOutSound(continuousClip);
    }
}
