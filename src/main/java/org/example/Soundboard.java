package org.example;

import javax.servlet.ServletOutputStream;
import javax.sound.sampled.*;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import org.glassfish.tyrus.server.Server;
import spark.Spark;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

@ServerEndpoint("/soundboard")
public class Soundboard {
    private static Clip goalClip;
    private static Clip alternateGoalClip;
    private static Clip songClip;
    private static Clip alternateSongClip;
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
    private static int homeScore = 0;
    private static int awayScore = 0;
    private static Set<Session> soundboardSessions = new CopyOnWriteArraySet<>();  // Sessions for Postman or external tool
    private static Set<Session> clientSessions = new CopyOnWriteArraySet<>();  // Sessions for the browser clients
    private static boolean enableWeb = true;

    static {
        try {
            basePath = Paths.get(Soundboard.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().toString();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try { goalClip            = loadClip("goal.wav");          }
        catch(Exception e) { e.printStackTrace(); }

        try { alternateGoalClip   = loadClip("alternateGoal.wav");     }
        catch(Exception e) { e.printStackTrace(); }

        try { songClip            = loadClip("song.wav");          }
        catch(Exception e) { e.printStackTrace(); }

        try { alternateSongClip   = loadClip("alternateSong.wav");      }
        catch(Exception e) { e.printStackTrace(); }

        try { continuousClip      = loadClip("crowd.wav");         }
        catch(Exception e) { e.printStackTrace(); }
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
        volumeControl.setValue(volumeControl.getMaximum() - 10.0f);
    }

    public static void main(String[] args) {
        // Create a set of classes that represent WebSocket endpoints
        Set<Class<?>> endpoints = new HashSet<>();
        endpoints.add(Soundboard.class);
        endpoints.add(Soundboard.ClientEndpoint.class);  // Register the client WebSocket endpoint

        // Start the WebSocket server with both soundboard and client endpoints
        Server server = new Server("localhost", 8080, "/", null, endpoints);


        if (enableWeb) {
            // Start static file server (Spark for serving HTML and static assets)
            Spark.port(4567);
            Spark.staticFiles.location("/public");
            Spark.get("/", (req, res) -> {
                try {
                    String htmlFilePath = Paths.get("public/index.html").toString();
                    return new String(Files.readAllBytes(Paths.get(htmlFilePath)));
                } catch (IOException e) {
                    e.printStackTrace();
                    res.status(500);
                    return "Error loading page";
                }
            });

            try {
                server.start();
                System.out.println("WebSocket server started.");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        server.stop();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }));
            } catch (Exception e) {
                e.printStackTrace();
            }

            Spark.get("/proxy/:playerId", (req, res) -> {
                // Get the playerId from the URL parameters
                String playerId = req.params("playerId");
                String targetUrl = "https://api-web.nhle.com/v1/player/" + playerId + "/landing";

                // Call the target URL using HttpURLConnection
                String response = forwardRequestToTargetUrl(targetUrl);

                if (response == null) {
                    res.status(500);
                    return "Error while proxying request";
                }

                res.type("application/json");
                return response;
            });

            Spark.get("/proxyImage", (req, res) -> {
                // Get the image URL as a query parameter
                String imageUrl = req.queryParams("url");

                if (imageUrl == null || imageUrl.isEmpty()) {
                    res.status(400);
                    return "Missing image URL";
                }

                // Forward request to the target image URL
                HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setRequestMethod("GET");

                int statusCode = connection.getResponseCode();
                if (statusCode != 200) {
                    res.status(statusCode);
                    return "Failed to retrieve image from " + imageUrl;
                }

                // Get content type from the target server (e.g., image/jpeg or image/png)
                String contentType = connection.getContentType();
                res.type(contentType);

                try (InputStream inputStream = connection.getInputStream()) {
                    // Write image data to the response output stream
                    ServletOutputStream outputStream = res.raw().getOutputStream();
                    byte[] buffer = new byte[4096];
                    int bytesRead;

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }

                    outputStream.flush();
                } catch (Exception e) {
                    res.status(500);
                    return "Error while proxying the image";
                }

                return res.raw(); // Return the response with image data
            });

            // Proxy to get real-time play-by-play data for a game
            Spark.get("/proxyGame/:gameId", (req, res) -> {
                String gameId = req.params("gameId");
                String targetUrl = "https://api-web.nhle.com/v1/gamecenter/" + gameId + "/play-by-play";

                String response = forwardRequestToTargetUrl(targetUrl);

                if (response == null) {
                    res.status(500);
                    return "Error while proxying request";
                }

                res.type("application/json");
                return response;
            });

// Proxy to get the landing data (includes clock.timeRemaining)
            Spark.get("/proxyLanding/:gameId", (req, res) -> {
                String gameId = req.params("gameId");
                String targetUrl = "https://api-web.nhle.com/v1/gamecenter/" + gameId + "/landing";

                String response = forwardRequestToTargetUrl(targetUrl);
                if (response == null) {
                    res.status(500);
                    return "Error while proxying landing request";
                }

                res.type("application/json");
                return response;
            });

            Spark.get("/convertSvgToPng", (req, res) -> {
                String svgUrl = req.queryParams("svgUrl");  // Get the SVG URL from the request query parameters

                if (svgUrl == null || svgUrl.isEmpty()) {
                    res.status(400);
                    return "Missing SVG URL";
                }

                try {
                    // Convert the SVG to PNG
                    ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
                    convertSvgToPng(svgUrl, pngOutputStream);

                    // Serve the PNG as a response
                    res.type("image/png");
                    byte[] pngData = pngOutputStream.toByteArray();
                    res.raw().setContentLength(pngData.length);
                    OutputStream out = res.raw().getOutputStream();
                    out.write(pngData);
                    out.flush();
                    return res.raw();
                } catch (Exception e) {
                    e.printStackTrace();
                    res.status(500);
                    return "Error converting SVG to PNG";
                }
            });


            Spark.awaitInitialization();


        }
    }

    private static String forwardRequestToTargetUrl(String targetUrl) {
        try {
            // Create a URL object with the target API URL
            URL url = new URL(targetUrl);
            System.out.println("getting player info");

            // Open a connection to the URL
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");

            // Check if the request was successful (HTTP 200 OK)
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                // Read the response from the input stream
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }

                // Close the connections
                in.close();
                connection.disconnect();

                // Return the content of the response
                return content.toString();
            } else {
                System.out.println("Error: Failed to fetch data, response code: " + responseCode);
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }


    @OnOpen
    public void onOpen(Session session) {
        System.out.println("Connection opened: " + session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session) throws IOException {
        System.out.println(message);
        switch (message) {
            case "goal_push_panthers":
                if (!continuousClipPlaying && (songClip == null || !songClip.isRunning())) {
                    playContinuousClip();
                }
                playClip(goalClip);
                broadcastPanthersGoal();
                break;
            case "goal_release":
                fadeOutSound(goalClip);
                fadeOutSound(alternateGoalClip);
                break;
            case "panther_song":
                playClip(songClip);
                break;
            case "alternate_song":
                playClip(alternateSongClip);
                break;
            case "goal_push_alternate":
                if (!continuousClipPlaying && (alternateSongClip == null || !alternateSongClip.isRunning())) {
                    playContinuousClip();
                }
                playClip(alternateGoalClip);
                broadcastLightningGoal();

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
                if (clip == alternateSongClip) thirdSongClipFadingOut = true;
                if (clip == continuousClip) continuousClipFadingOut = true;

                FloatControl volumeControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                volumeControl.setValue(volumeControl.getMaximum() - 10.0f);
                float initialVolume = volumeControl.getValue();
                float minVolume = volumeControl.getMinimum();
                int steps = 400; // More steps for a smoother fade-out
                float stepSize = (initialVolume - minVolume) / steps;

                for (int i = 0; i <= steps; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        setVolumeToMax(clip);
                        return;
                    }
                    float newVolume = initialVolume - stepSize * i;
                    volumeControl.setValue(Math.max(newVolume, minVolume));
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
                if (clip == alternateSongClip) thirdSongClipFadingOut = false;
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
        fadeOutSound(alternateSongClip);
        continuousClipPlaying = false;
        stopVideo();
    }


    // New endpoint specifically for the client (browser)
    @ServerEndpoint("/client")
    public static class ClientEndpoint {
        @OnOpen
        public void onClientOpen(Session session) {
            clientSessions.add(session);  // Add session for the browser client
            System.out.println("Client connection opened: " + session.getId());
        }

        @OnClose
        public void onClientClose(Session session) {
            clientSessions.remove(session);  // Remove session when closed
            System.out.println("Client connection closed: " + session.getId());
        }

        @OnMessage
        public void onClientMessage(String message, Session session) {
            System.out.println("Received from client: " + message);
        }
    }

    // Method to broadcast the score update to both soundboard and browser
    private static void broadcastScoreUpdate() throws IOException {
        String scoreUpdate = "home:" + homeScore + ",away:" + awayScore;

        // Broadcast to soundboard sessions
        for (Session session : soundboardSessions) {
            try {
                session.getBasicRemote().sendText(scoreUpdate);
            } catch (IOException e) {
                e.printStackTrace();
            }
            session.getBasicRemote().sendText("pressed");
        }

        // Broadcast to client (browser) sessions
        for (Session session : clientSessions) {
            try {
                session.getBasicRemote().sendText(scoreUpdate);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void broadcastPanthersGoal() {
        String testMessage = "spressed";

        // Broadcast to client (browser) sessions
        for (Session session : clientSessions) {
            try {
                session.getBasicRemote().sendText(testMessage);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void broadcastLightningGoal() {
        String testMessage = "lpressed";

        // Broadcast to client (browser) sessions
        for (Session session : clientSessions) {
            try {
                session.getBasicRemote().sendText(testMessage);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void stopVideo() {
        String testMessage = "all_stop";

        // Broadcast to client (browser) sessions
        for (Session session : clientSessions) {
            try {
                session.getBasicRemote().sendText(testMessage);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void convertSvgToPng(String svgUrl, OutputStream outputStream) throws IOException, TranscoderException {
        // Fetch the SVG content from the URL
        InputStream svgInputStream = new URL(svgUrl).openStream();

        // Create a PNGTranscoder object
        PNGTranscoder transcoder = new PNGTranscoder();

        // Input the SVG data
        TranscoderInput input = new TranscoderInput(svgInputStream);

        // Output the PNG to the provided OutputStream
        TranscoderOutput output = new TranscoderOutput(outputStream);

        // Perform the conversion
        transcoder.transcode(input, output);

        // Close the input stream
        svgInputStream.close();
    }



}