package com.torxone.app.transport.tor;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Framework-only release capture. RESULT_OK means capture ran, never that Tor is ready. */
public final class NativeTorReadinessInstrumentation extends Instrumentation {
    private Bundle arguments;
    private static final Pattern PROGRESS = Pattern.compile("(?:^|\\s)PROGRESS=(\\d{1,3})(?:\\s|$)");
    private static final Pattern LOOPBACK = Pattern.compile("\"(127\\.0\\.0\\.1|\\[::1\\]):(\\d{1,5})\"");

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : new Bundle(args);
        start();
    }

    @Override public void onStart() {
        Bundle finished = new Bundle();
        finished.putString("probeMeaning", "CAPTURE_ONLY");
        try {
            long delay = 0;
            try { delay = Long.parseLong(arguments.getString("snapshotDelayMs", "0")); }
            catch (NumberFormatException ignored) { }
            delay = Math.max(0, Math.min(30_000, delay));
            int repeats = delay > 0 ? 3 : 0;
            for (int sample = 0; sample <= repeats; sample++) {
                if (sample > 0) SystemClock.sleep(delay / repeats);
                Bundle result;
                try { result = snapshot(); }
                catch (Throwable error) { result = failure("capture", "UNAVAILABLE", errorClass(error)); }
                result.putString("sample", sample == 0 ? "INITIAL" : sample == repeats ? "FINAL" : "INTERMEDIATE");
                result.putString("probeMeaning", "CAPTURE_ONLY");
                sendStatus(0, result);
            }
            finished.putBoolean("captureExecuted", true);
            finish(Activity.RESULT_OK, finished);
        } catch (Throwable error) {
            finished.putBoolean("captureExecuted", false);
            finished.putString("error", errorClass(error));
            finish(Activity.RESULT_CANCELED, finished);
        }
    }

    private Bundle snapshot() throws IOException, ReflectiveOperationException {
        Context context = getTargetContext();
        File privateRoot = new File(context.getApplicationInfo().dataDir).getCanonicalFile();
        // Reflect only this preserved dependency's public API, avoiding its newer classfile
        // version in javac while leaving the release dependency and keep rules unchanged.
        File torrc = ((File) Class.forName("org.torproject.jni.TorService")
                .getMethod("getTorrc", Context.class).invoke(null, context)).getCanonicalFile();
        File root = torrc.getParentFile();
        if (root == null || !inside(torrc, privateRoot)) return failure("capture", "NO_PRIVATE_ROOT", null);
        Map<String, String> directives = new HashMap<>();
        if (torrc.isFile() && torrc.length() <= 65_536) {
            try {
                String text = new String(readBounded(torrc, 65_536), StandardCharsets.UTF_8);
                for (String source : text.split("\\r?\\n")) {
                    String line = source.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int split = 0;
                    while (split < line.length() && !Character.isWhitespace(line.charAt(split))) split++;
                    if (split < line.length()) directives.put(line.substring(0, split).toLowerCase(Locale.ROOT), stripQuotes(line.substring(split).trim()));
                }
            } catch (IOException ignored) { }
        }
        File data = privateFile(directives.get("datadirectory"), new File(root, "data"), root, privateRoot);
        File control = privateFile(directives.get("controlsocket"), new File(data == null ? new File(root, "data") : data, "ControlSocket"), root, privateRoot);
        File onion = privateFile(directives.get("hiddenservicedir"), new File(privateRoot, "app_torx_onion_v3"), root, privateRoot);
        File hostname = onion == null ? null : privateFile(null, new File(onion, "hostname"), root, privateRoot);
        Bundle result = new Bundle();
        result.putString("capture", "CAPTURED");
        result.putBoolean("torrcPresent", torrc.isFile());
        result.putBoolean("dataDirectoryPresent", data != null && data.isDirectory());
        result.putBoolean("controlSocketConfigured", directives.containsKey("controlsocket"));
        result.putBoolean("controlSocketPresent", control != null && control.exists());
        result.putBoolean("controlSocketPrivate", ownedPrivate(control));
        result.putBoolean("onionDirectoryPresent", onion != null && onion.isDirectory());
        result.putBoolean("onionDirectoryPrivate", ownedPrivate(onion));
        result.putBoolean("hostnamePresent", hostname != null && hostname.isFile());
        result.putBoolean("hostnameNonempty", hostname != null && hostname.isFile() && hostname.length() > 0);
        boolean hostnameValid = false;
        if (hostname != null && hostname.isFile() && hostname.length() > 0 && hostname.length() <= 128) {
            try { hostnameValid = new String(readBounded(hostname, 128), StandardCharsets.US_ASCII).trim().matches("[a-z2-7]{56}\\.onion"); }
            catch (IOException ignored) { }
        }
        result.putBoolean("hostnameFormatValid", hostnameValid);
        if (control == null) result.putString("control", "OUTSIDE_PRIVATE_ROOT");
        else result.putAll(probeControl(control));
        return result;
    }

    private static File privateFile(String configured, File fallback, File root, File privateRoot) {
        try {
            File file = configured == null ? fallback : new File(configured);
            if (!file.isAbsolute()) file = new File(root, file.getPath());
            file = file.getCanonicalFile();
            return inside(file, privateRoot) ? file : null;
        } catch (IOException ignored) { return null; }
    }

    private static boolean inside(File file, File root) { return file.getPath().startsWith(root.getPath() + File.separator); }

    private static byte[] readBounded(File file, int limit) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[Math.min(4_096, limit + 1)];
            while (bytes.size() <= limit) {
                int count = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - bytes.size()));
                if (count < 0) return bytes.toByteArray();
                bytes.write(buffer, 0, count);
            }
            throw new IOException("Probe read limit");
        }
    }

    private static boolean ownedPrivate(File file) {
        if (file == null) return false;
        try {
            StructStat stat = Os.stat(file.getPath());
            return stat.st_uid == Process.myUid() && (stat.st_mode & 63) == 0;
        } catch (ErrnoException ignored) { return false; }
    }

    private static Bundle probeControl(final File endpoint) {
        final LocalSocket socket = new LocalSocket();
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicReference<Socket> tcpOwner = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "native-tor-framework-probe");
                thread.setDaemon(true);
                return thread;
            }
        });
        Future<Bundle> future = executor.submit(new Callable<Bundle>() {
            @Override public Bundle call() {
                Bundle result = new Bundle();
                result.putString("control", "CONNECTING");
                result.putString("authentication", "NOT_ATTEMPTED");
                try {
                    // Public lazy descriptor creation allows options to bound connect itself.
                    socket.getInputStream();
                    socket.setSoTimeout(2_000);
                    socket.connect(new LocalSocketAddress(endpoint.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
                    result.putString("control", "CONNECTED");
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    List<String> auth = command(input, output, "AUTHENTICATE");
                    String authentication = responseClass(auth.get(auth.size() - 1));
                    result.putString("authentication", authentication);
                    if ("SUCCESS".equals(authentication)) {
                        List<String> reply = command(input, output, "GETINFO status/bootstrap-phase net/listeners/socks");
                        result.putString("controlResponse", responseClass(reply.get(reply.size() - 1)));
                        String bootstrap = field(reply, "status/bootstrap-phase");
                        result.putBoolean("bootstrapReported", bootstrap != null);
                        if (bootstrap != null) {
                            Matcher progress = PROGRESS.matcher(bootstrap);
                            if (progress.find()) {
                                int percent = Integer.parseInt(progress.group(1));
                                if (percent >= 0 && percent <= 100) result.putInt("bootstrapPercent", percent);
                            }
                        }
                        String socks = field(reply, "net/listeners/socks");
                        result.putBoolean("socksListenersReported", socks != null);
                        result.putBoolean("socksListenerPresent", socks != null && !stripQuotes(socks.trim()).isEmpty());
                        result.putBoolean("socksHandshakeSucceeded", socks != null && socksHandshake(socks, tcpOwner, stopped));
                    }
                } catch (Exception error) {
                    result.putString("control", "IO_FAILURE");
                    result.putString("error", errorClass(error));
                } finally { closeLocal(socket); }
                return result;
            }
        });
        try { return future.get(2_000, TimeUnit.MILLISECONDS); }
        catch (TimeoutException ignored) { return failure("control", "DEADLINE", "TIMEOUT"); }
        catch (Exception error) { return failure("control", "IO_FAILURE", errorClass(error)); }
        finally {
            stopped.set(true);
            closeLocal(socket);
            closeTcp(tcpOwner.getAndSet(null));
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    private static List<String> command(InputStream input, OutputStream output, String command) throws IOException {
        output.write((command + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
        List<String> lines = new ArrayList<>();
        for (int count = 0; count < 16; count++) {
            String line = readLine(input);
            if (line.length() < 4 || !line.substring(0, 3).matches("[0-9]{3}") || (line.charAt(3) != '-' && line.charAt(3) != ' ')) throw new ProtocolException();
            lines.add(line);
            if (line.charAt(3) == ' ') return lines;
        }
        throw new ProtocolException();
    }

    private static String field(List<String> reply, String key) {
        for (String line : reply) {
            if (line.startsWith("250-" + key + "=") || line.startsWith("250 " + key + "=")) return line.substring(line.indexOf('=') + 1);
        }
        return null;
    }

    private static boolean socksHandshake(String listeners, AtomicReference<Socket> owner, AtomicBoolean stopped) {
        Matcher address = LOOPBACK.matcher(listeners);
        for (int attempt = 0; attempt < 2 && address.find(); attempt++) {
            int port = Integer.parseInt(address.group(2));
            if (port < 1 || port > 65_535) continue;
            Socket socket = new Socket();
            owner.set(socket);
            try {
                if (stopped.get()) return false;
                String host = address.group(1);
                if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
                socket.connect(new InetSocketAddress(host, port), 500);
                socket.setSoTimeout(500);
                socket.getOutputStream().write(new byte[]{5, 1, 0});
                socket.getOutputStream().flush();
                InputStream input = socket.getInputStream();
                if (input.read() == 5 && input.read() == 0) return true;
            } catch (IOException ignored) { }
            finally { closeTcp(socket); owner.compareAndSet(socket, null); }
        }
        return false;
    }

    private static void closeLocal(LocalSocket socket) {
        try { socket.shutdownInput(); } catch (IOException ignored) { }
        try { socket.shutdownOutput(); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static void closeTcp(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 4_096) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            if (next == 10) return bytes.toString("US-ASCII").replaceFirst("\\r$", "");
            bytes.write(next);
        }
        throw new ProtocolException();
    }

    private static String responseClass(String line) {
        if (line.startsWith("250")) return "SUCCESS";
        if (line.startsWith("514")) return "AUTH_REQUIRED";
        if (line.startsWith("515")) return "AUTH_REJECTED";
        if (line.startsWith("5")) return "SERVER_REJECTED";
        return "UNEXPECTED_RESPONSE";
    }

    private static String stripQuotes(String text) {
        return text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"' ? text.substring(1, text.length() - 1) : text;
    }

    private static Bundle failure(String key, String value, String error) {
        Bundle result = new Bundle();
        result.putString(key, value);
        if (error != null) result.putString("error", error);
        return result;
    }

    private static String errorClass(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; depth < 8 && cause != null; depth++, cause = cause.getCause()) {
            if (cause instanceof ErrnoException) return errnoClass(((ErrnoException) cause).errno);
            if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) return "TIMEOUT";
            if (cause instanceof EOFException) return "EOF";
            if (cause instanceof ProtocolException) return "PROTOCOL";
            if (cause instanceof SecurityException) return "ACCESS_DENIED";
            if (cause instanceof IOException) {
                int[] errors = {OsConstants.ENOENT, OsConstants.ECONNREFUSED, OsConstants.EACCES, OsConstants.ETIMEDOUT, OsConstants.EAGAIN};
                for (int code : errors) if (Os.strerror(code).equals(cause.getMessage())) return errnoClass(code);
            }
        }
        return "OTHER_FAILURE";
    }

    private static String errnoClass(int errno) {
        if (errno == OsConstants.ENOENT) return "ENOENT";
        if (errno == OsConstants.ECONNREFUSED) return "ECONNREFUSED";
        if (errno == OsConstants.EACCES) return "EACCES";
        if (errno == OsConstants.ETIMEDOUT) return "ETIMEDOUT";
        if (errno == OsConstants.EAGAIN) return "EAGAIN";
        return "OTHER_ERRNO";
    }

    private static final class ProtocolException extends IOException { }
}
