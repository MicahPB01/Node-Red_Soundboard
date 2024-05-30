package org.example;

import javax.sound.sampled.*;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import org.glassfish.tyrus.server.Server;

import java.io.File;
import java.io.IOException;
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
                    playContinuousSound("C:\\Users\\micah\\Downloads\\Crowd.wav");
                }
                playGoalSound("C:\\Users\\micah\\Downloads\\GOAL(Long).wav");
                break;
            case "released":
                fadeOutSound(goalClip);
                break;
            case "song":
                playSong("C:\\Users\\micah\\Downloads\\Song.wav");
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

    private static void playGoalSound(String filePath) {
        executorService.submit(() -> playSound(filePath, true));
    }

    private static void playSong(String filePath) {
        executorService.submit(() -> playSound(filePath, false));
    }

    private static void playSound(String filePath, boolean isGoalSound) {
        try {
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new File(filePath));
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

    private static void playContinuousSound(String filePath) {
        executorService.submit(() -> {
            try {
                AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new File(filePath));
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
                for (int i = 0; i <= 100; i++) {
                    float newVolume = initialVolume - (initialVolume - volume.getMinimum()) * (i / 100.0f);
                    volume.setValue(newVolume);
                    try {
                        Thread.sleep(15); // Total of 1.5 seconds for fade-out
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
