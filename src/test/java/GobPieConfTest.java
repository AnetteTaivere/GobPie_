import com.google.gson.Gson;
import gobpie.GobPieConfReader;
import gobpie.GobPieConfiguration;
import gobpie.GobPieException;
import magpiebridge.core.MagpieServer;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.stream.SystemOut;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(SystemStubsExtension.class)
class GobPieConfTest {

    @TempDir
    private Path tempDir;

    @SystemStub
    private SystemOut systemOut;

    @Test
    void testReadGobPieConfiguration() {
        // Mock everything needed for creating GobPieConfReader
        MagpieServer magpieServer = mock(MagpieServer.class);
        String gobPieConfFileName = GobPieConfTest.class.getResource("gobpieTest1.json").getFile();
        GobPieConfReader gobPieConfReader = new GobPieConfReader(magpieServer, gobPieConfFileName);

        GobPieConfiguration expectedGobPieConfiguration =
                new GobPieConfiguration.Builder()
                        .setGoblintConf("goblint.json")
                        .setGoblintExecutable("/home/user/goblint/analyzer/goblint")
                        .setPreAnalyzeCommand(new String[]{"echo", "'hello'"})
                        .setAbstractDebugging(true)
                        .setShowCfg(true)
                        .setIncrementalAnalysis(false)
                        .setExplodeGroupWarnings(true)
                        .createGobPieConfiguration();

        GobPieConfiguration actualGobPieConfiguration = gobPieConfReader.readGobPieConfiguration();

        assertTrue(systemOut.getLines().anyMatch(line -> line.contains("Reading GobPie configuration from json")));
        assertTrue(systemOut.getLines().anyMatch(line -> line.contains("GobPie configuration read from json")));
        assertEquals(expectedGobPieConfiguration, actualGobPieConfiguration);
    }

    @Test
    void testReadCompleteGobPieConfiguration() {
        // Mock everything needed for creating GobPieConfReader
        MagpieServer magpieServer = mock(MagpieServer.class);
        String gobPieConfFileName = GobPieConfTest.class.getResource("gobpieTest2.json").getFile();
        GobPieConfReader gobPieConfReader = new GobPieConfReader(magpieServer, gobPieConfFileName);

        GobPieConfiguration expectedGobPieConfiguration =
                new GobPieConfiguration.Builder()
                        .setGoblintConf("goblint.json")
                        .setGoblintExecutable("/home/user/goblint/analyzer/goblint")
                        .setPreAnalyzeCommand(new String[]{"cmake", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", "-B", "build"})
                        .setAbstractDebugging(false)
                        .setShowCfg(false)
                        .setIncrementalAnalysis(true)
                        .setExplodeGroupWarnings(false)
                        .createGobPieConfiguration();

        GobPieConfiguration actualGobPieConfiguration = gobPieConfReader.readGobPieConfiguration();

        assertTrue(systemOut.getLines().anyMatch(line -> line.contains("Reading GobPie configuration from json")));
        assertTrue(systemOut.getLines().anyMatch(line -> line.contains("GobPie configuration read from json")));
        assertEquals(expectedGobPieConfiguration, actualGobPieConfiguration);
    }

    @Test
    void testGobPieConfigurationDefaultValues() {
        // Mock everything needed for creating GobPieConfReader
        MagpieServer magpieServer = mock(MagpieServer.class);
        String gobPieConfFileName = GobPieConfTest.class.getResource("gobpieTest7.json").getFile();
        GobPieConfReader gobPieConfReader = new GobPieConfReader(magpieServer, gobPieConfFileName);

        GobPieConfiguration expectedGobPieConfiguration =
                new GobPieConfiguration.Builder()
                        .setGoblintConf("goblint.json")
                        .setGoblintExecutable("goblint")
                        .setPreAnalyzeCommand(null)
                        .setAbstractDebugging(false)
                        .setShowCfg(false)
                        .setIncrementalAnalysis(true)
                        .setExplodeGroupWarnings(true)
                        .createGobPieConfiguration();

        GobPieConfiguration actualGobPieConfiguration = gobPieConfReader.readGobPieConfiguration();

        assertTrue(systemOut.getLines().anyMatch(line -> line.contains("Reading GobPie configuration from json")));
        assertTrue(systemOut.getLines().anyMatch(line -> line.contains("GobPie configuration read from json")));
        assertEquals(expectedGobPieConfiguration, actualGobPieConfiguration);
    }

    @Test
    void testReadGobPieConfigurationWithWrongJSONSyntax() {
        // Mock everything needed for creating GobPieConfReader
        MagpieServer magpieServer = mock(MagpieServer.class);
        String gobPieConfFileName = GobPieConfTest.class.getResource("gobpieTest5.json").getFile();
        GobPieConfReader gobPieConfReader = new GobPieConfReader(magpieServer, gobPieConfFileName);

        GobPieException thrown = assertThrows(GobPieException.class, gobPieConfReader::readGobPieConfiguration);
        assertEquals("GobPie configuration file syntax is wrong.", thrown.getMessage());
    }

    @Test
    void testReadGobPieConfigurationWithExtraField() {
        // Mock everything needed for creating GobPieConfReader
        MagpieServer magpieServer = mock(MagpieServer.class);
        String gobPieConfFileName = GobPieConfTest.class.getResource("gobpieTest4.json").getFile();
        GobPieConfReader gobPieConfReader = new GobPieConfReader(magpieServer, gobPieConfFileName);

        GobPieConfiguration expectedGobPieConfiguration =
                new GobPieConfiguration.Builder()
                        .setGoblintConf("goblint.json")
                        .setGoblintExecutable("/home/user/goblint/analyzer/goblint")
                        .setPreAnalyzeCommand(new String[]{"cmake", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", "-B", "build"})
                        .setAbstractDebugging(false)
                        .setShowCfg(false)
                        .setIncrementalAnalysis(true)
                        .setExplodeGroupWarnings(false)
                        .createGobPieConfiguration();

        GobPieConfiguration actualGobPieConfiguration = gobPieConfReader.readGobPieConfiguration();

        assertTrue(systemOut.getLines().anyMatch(line -> line.contains("Reading GobPie configuration from json")));
        assertTrue(systemOut.getLines().anyMatch(line -> line.contains("GobPie configuration read from json")));
        assertEquals(expectedGobPieConfiguration, actualGobPieConfiguration);
    }

    @Test
    void testGobPieConfigurationFileMissingInRoot() throws IOException, ExecutionException, InterruptedException {
        // Mock everything needed for creating GobPieConfReader
        MagpieServer magpieServer = mock(MagpieServer.class);

        Path tempGobpieConfFilePath = tempDir.resolve("gobpie.json");
        GobPieConfReader gobPieConfReader = new GobPieConfReader(magpieServer, tempGobpieConfFilePath.toString());

        assertFalse(Files.exists(tempGobpieConfFilePath));

        CompletableFuture<GobPieConfiguration> future = CompletableFuture.supplyAsync(gobPieConfReader::readGobPieConfiguration);

        GobPieConfiguration expectedGobPieConfiguration =
                new GobPieConfiguration.Builder()
                        .setGoblintConf("goblint.json")
                        .setGoblintExecutable("goblint")
                        .setAbstractDebugging(false)
                        .setShowCfg(false)
                        .setIncrementalAnalysis(true)
                        .setExplodeGroupWarnings(false)
                        .createGobPieConfiguration();

        Thread.sleep(10); // TODO: real sketchy hack

        // Write the expected conf into temporary file
        Gson gson = new Gson();
        String json = gson.toJson(expectedGobPieConfiguration);
        Files.write(tempGobpieConfFilePath, json.getBytes());

        // Assert that the file was indeed not present;
        // the user is notified;
        // and the configuration is successfully read when the configuration file is created
        try {
            GobPieConfiguration actualGobPieConfiguration = future.get(100, TimeUnit.MILLISECONDS);
            String message = "GobPie configuration file is not found in the project root.";
            verify(magpieServer).forwardMessageToClient(new MessageParams(MessageType.Error, "Problem starting GobPie extension: " + message + " Check the output terminal of GobPie extension for more information."));
            assertTrue(systemOut.getLines().anyMatch(line -> line.contains(message)));
            assertTrue(systemOut.getLines().anyMatch(line -> line.contains("Please add GobPie configuration file into the project root.")));
            assertEquals(expectedGobPieConfiguration, actualGobPieConfiguration);
        } catch (TimeoutException e) {
            fail("Test timeout");
        }
    }

    @Test
    void testGobPieConfigurationWithoutGoblintConfField() throws IOException, ExecutionException, InterruptedException {
        // Mock everything needed for creating GobPieConfReader
        MagpieServer magpieServer = mock(MagpieServer.class);
        Path tempGobpieConfFilePath = tempDir.resolve("gobpie.json");
        GobPieConfReader gobPieConfReader = new GobPieConfReader(magpieServer, tempGobpieConfFilePath.toString());

        GobPieConfiguration.Builder builder = new GobPieConfiguration.Builder()
                .setGoblintExecutable("/home/user/goblint/analyzer/goblint")
                .setPreAnalyzeCommand(new String[]{"echo", "'hello'"})
                .setAbstractDebugging(false)
                .setShowCfg(false)
                .setIncrementalAnalysis(true)
                .setExplodeGroupWarnings(false);
        GobPieConfiguration initialGobPieConfiguration = builder.createGobPieConfiguration();

        // Write the initial conf into temporary file
        Gson gson = new Gson();
        String json = gson.toJson(initialGobPieConfiguration);
        Files.write(tempGobpieConfFilePath, json.getBytes());
        assertTrue(Files.exists(tempGobpieConfFilePath));

        CompletableFuture<GobPieConfiguration> future = CompletableFuture.supplyAsync(gobPieConfReader::readGobPieConfiguration);

        GobPieConfiguration expectedGobPieConfiguration = builder.setGoblintConf("goblint.json").createGobPieConfiguration();

        // Write goblintConf field to file
        Files.write(tempGobpieConfFilePath, gson.toJson(expectedGobPieConfiguration).getBytes());

        // Assert that the configuration was read;
        // (the required field was indeed not present; the user is notified;)
        // and the configuration is successfully read when the goblintConf field is added to the file.
        try {
            GobPieConfiguration actualGobPieConfiguration = future.get(100, TimeUnit.MILLISECONDS);
            assertTrue(systemOut.getLines().anyMatch(line -> line.contains("Reading GobPie configuration from json")));
            assertTrue(systemOut.getLines().anyMatch(line -> line.contains("GobPie configuration read from json")));
            // We cannot check the following for sure as the file can be changed too quickly
            //String message = "goblintConf parameter missing from GobPie configuration file.";
            //verify(magpieServer).forwardMessageToClient(new MessageParams(MessageType.Error, "Problem starting GobPie extension: " + message + " Check the output terminal of GobPie extension for more information."));
            //assertTrue(systemOut.getLines().anyMatch(line -> line.contains(message)));
            //assertTrue(systemOut.getLines().anyMatch(line -> line.contains("Please add Goblint configuration file location into GobPie configuration as a parameter with name \"goblintConf\"")));
            // But we can test that when readGobPieConfiguration() completes, it will always give a valid configuration
            assertEquals(expectedGobPieConfiguration, actualGobPieConfiguration);
        } catch (TimeoutException e) {
            fail("Test timeout");
        }
    }

    @Test
    void testGobPieConfigurationWithoutGoblintConfField2() throws ExecutionException, InterruptedException {
        // Mock everything needed for creating GobPieConfReader
        MagpieServer magpieServer = mock(MagpieServer.class);
        String gobPieConfFileName = GobPieConfTest.class.getResource("gobpieTest6.json").getFile();
        GobPieConfReader gobPieConfReader = new GobPieConfReader(magpieServer, gobPieConfFileName);

        CompletableFuture<GobPieConfiguration> future = CompletableFuture.supplyAsync(gobPieConfReader::readGobPieConfiguration);

        // Assert that the configuration was read;
        // the required field was indeed not present;
        // and the user is notified.
        try {
            future.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Only check for the user notification
            assertTrue(systemOut.getLines().anyMatch(line -> line.contains("Reading GobPie configuration from json")));
            assertTrue(systemOut.getLines().anyMatch(line -> line.contains("GobPie configuration read from json")));
            String message = "goblintConf parameter missing from GobPie configuration file.";
            verify(magpieServer).forwardMessageToClient(new MessageParams(MessageType.Error, "Problem starting GobPie extension: " + message + " Check the output terminal of GobPie extension for more information."));
            assertTrue(systemOut.getLines().anyMatch(line -> line.contains(message)));
            assertTrue(systemOut.getLines().anyMatch(line -> line.contains("Please add Goblint configuration file location into GobPie configuration as a parameter with name \"goblintConf\"")));
        }
    }

}
