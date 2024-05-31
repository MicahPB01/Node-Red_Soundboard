package org.example;

import javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader;
import org.tritonus.share.sampled.file.TAudioFileFormat;

import javax.sound.sampled.*;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import org.glassfish.tyrus.server.Server;

import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ServerEndpoint("/soundboard")
public class Soundboard {
    private static SourceDataLine goalLine;
    private static SourceDataLine songLine;
    private static SourceDataLine continuousLine;
    private static boolean isFadingOut = false;
    private static final CountDownLatch latch = new CountDownLatch(1);
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static boolean continuousLinePlaying = false;

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
                if (!continuousLinePlaying && (songLine == null || !songLine.isOpen())) {
                    playContinuousSound("Crowd(LOW).ogg");
                }
                playGoalSound("GOAL(Long)(LOW).ogg");
                break;
            case "released":
                fadeOutSound(goalLine);
                break;
            case "main":
                playSong("Song(LOW).ogg");
                scheduler.schedule(() -> fadeOutSound(continuousLine), 5, TimeUnit.SECONDS);
                break;
            case "alternate":
                playSong("Song(Alternate)(LOW).ogg");
                scheduler.schedule(() -> fadeOutSound(continuousLine), 5, TimeUnit.SECONDS);
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
        executorService.submit(() -> playOGG(filePath, true));
    }

    private static void playSong(String filePath) {
        executorService.submit(() -> playOGG(filePath, false));
    }

    private static void playOGG(String filePath, boolean isGoalSound) {
        try {
            File file = new File(filePath);
            VorbisAudioFileReader reader = new VorbisAudioFileReader();
            AudioInputStream audioInputStream = reader.getAudioInputStream(file);
            AudioFormat baseFormat = audioInputStream.getFormat();
            AudioFormat decodedFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false);
            AudioInputStream decodedAudioInputStream = AudioSystem.getAudioInputStream(decodedFormat, audioInputStream);
            SourceDataLine line = AudioSystem.getSourceDataLine(decodedFormat);
            line.open(decodedFormat, 8192); // Increase buffer size
            line.start();

            if (isGoalSound) {
                if (goalLine != null && goalLine.isOpen()) {
                    goalLine.stop();
                    goalLine.close();
                }
                goalLine = line;
            } else {
                if (songLine != null && songLine.isOpen()) {
                    songLine.stop();
                    songLine.close();
                }
                songLine = line;
            }

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = decodedAudioInputStream.read(buffer, 0, buffer.length)) != -1) {
                line.write(buffer, 0, bytesRead);
            }

            line.drain();
            line.stop();
            line.close();

            isFadingOut = false;
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            e.printStackTrace();
        }
    }

    private static void playContinuousSound(String filePath) {
        executorService.submit(() -> {
            try {
                File file = new File(filePath);
                VorbisAudioFileReader reader = new VorbisAudioFileReader();
                AudioInputStream audioInputStream = reader.getAudioInputStream(file);
                AudioFormat baseFormat = audioInputStream.getFormat();
                AudioFormat decodedFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.getSampleRate(),
                        16,
                        baseFormat.getChannels(),
                        baseFormat.getChannels() * 2,
                        baseFormat.getSampleRate(),
                        false);
                AudioInputStream decodedAudioInputStream = AudioSystem.getAudioInputStream(decodedFormat, audioInputStream);
                continuousLine = AudioSystem.getSourceDataLine(decodedFormat);
                continuousLine.open(decodedFormat, 8192); // Increase buffer size
                continuousLine.start();
                continuousLinePlaying = true;

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = decodedAudioInputStream.read(buffer, 0, buffer.length)) != -1) {
                    continuousLine.write(buffer, 0, bytesRead);
                }

                continuousLine.drain();
                continuousLine.stop();
                continuousLine.close();
                continuousLinePlaying = false;
            } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
                e.printStackTrace();
            }
        });
    }

    private static void fadeOutSound(SourceDataLine line) {
        if (line != null && line.isRunning() && !isFadingOut) {
            isFadingOut = true;
            new Thread(() -> {
                try {
                    FloatControl volumeControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                    float initialVolume = volumeControl.getValue();
                    for (int i = 0; i <= 100; i++) {
                        float newVolume = initialVolume - (initialVolume - volumeControl.getMinimum()) * (i / 100.0f);
                        volumeControl.setValue(newVolume);
                        try {
                            Thread.sleep(25); // Total of 2.5 seconds for fade-out
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    line.stop();
                    line.close();
                } catch (IllegalArgumentException e) {
                    // MASTER_GAIN control not supported, just stop the line
                    line.stop();
                    line.close();
                }
                isFadingOut = false;
            }).start();
        }
    }

    private static void stopAllSounds() {
        fadeOutSound(goalLine);
        fadeOutSound(songLine);
        fadeOutSound(continuousLine);
    }
}
