/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.fml.loading.moddiscovery;

import cpw.mods.gross.Java9ClassLoaderUtil;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.ServiceLoaderStreamUtils;
import cpw.mods.modlauncher.api.LamdbaExceptionUtils;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.ModSorter;
import net.minecraftforge.fml.loading.moddiscovery.sync.FTPService;
import net.minecraftforge.fml.loading.progress.StartupMessageManager;
import net.minecraftforge.forgespi.Environment;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSigner;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipError;

import static net.minecraftforge.fml.loading.LogMarkers.CORE;
import static net.minecraftforge.fml.loading.LogMarkers.SCAN;


public class ModDiscoverer {
    private static final Path INVALID_PATH = Paths.get("This", "Path", "Should", "Never", "Exist", "Because", "That", "Would", "Be", "Stupid", "CON", "AUX", "/dev/null");
    private static final Logger LOGGER = LogManager.getLogger();
    private final long DOWNLOAD_STATUS_CHECK_MILLIS = 1000;
    private final String EXCLUDED_FOLDER = "excluded";
    private final String DOWNLOAD_FOLDER = "downloads";
    private final ServiceLoader<IModLocator> locators;
    private final List<IModLocator> locatorList;
    private final LocatorClassLoader locatorClassLoader;

    public ModDiscoverer(Map<String, ?> arguments) {
        Launcher.INSTANCE.environment().computePropertyIfAbsent(Environment.Keys.MODFOLDERFACTORY.get(), v->ModsFolderLocator::new);
        Launcher.INSTANCE.environment().computePropertyIfAbsent(Environment.Keys.MODDIRECTORYFACTORY.get(), v->ModsFolderLocator::new);
        Launcher.INSTANCE.environment().computePropertyIfAbsent(Environment.Keys.PROGRESSMESSAGE.get(), v->StartupMessageManager.locatorConsumer().orElseGet(()->s->{}));
        locatorClassLoader = new LocatorClassLoader();
        Launcher.INSTANCE.environment().computePropertyIfAbsent(FMLEnvironment.Keys.LOCATORCLASSLOADER.get(), v->locatorClassLoader);
        ModDirTransformerDiscoverer.getExtraLocators()
                .stream()
                .map(LamdbaExceptionUtils.rethrowFunction(p->p.toUri().toURL()))
                .forEach(locatorClassLoader::addURL);
        locators = ServiceLoader.load(IModLocator.class, locatorClassLoader);
        locatorList = ServiceLoaderStreamUtils.toList(this.locators);
        locatorList.forEach(l->l.initArguments(arguments));
        locatorList.add(new MinecraftLocator());
        LOGGER.debug(CORE,"Found Mod Locators : {}", ()->locatorList.stream().map(iModLocator -> "("+iModLocator.name() + ":" + iModLocator.getClass().getPackage().getImplementationVersion()+")").collect(Collectors.joining(",")));
    }

    ModDiscoverer(List<IModLocator> locatorList) {
        this.locatorList = locatorList;
        this.locatorClassLoader = null;
        this.locators = null;
    }

    public void syncingMods(){
        LOGGER.debug(SCAN,"Sync for mods from lokitas server");
        ModsFolderLocator modsFolderLocator = locatorList.stream()
                .filter(ModsFolderLocator.class::isInstance)
                .map (ModsFolderLocator.class::cast)
                .findFirst()
                .get();

        if(modsFolderLocator == null)
            return;

        LOGGER.debug(SCAN,"Identifying mods from server.");
        Path excludeFolder = Paths.get(modsFolderLocator.folder().toString(),EXCLUDED_FOLDER);
        Path downloadFolder = Paths.get(modsFolderLocator.folder().toString(),DOWNLOAD_FOLDER);
        final List<Path> remoteMods = FTPService.getInstance().listMods();
        final List<String> remoteModNames = remoteMods
                .stream()
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(Collectors.toList());

        if(remoteMods.size() == 0) {
            LOGGER.debug(SCAN,"{} mods identified, sync process skipped",remoteMods.size());
            return;
        }
        else
            LOGGER.debug(SCAN,"{} mods identified.",remoteMods.size());

        StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept(remoteMods.size()+" mods required to run the client."));
        for (Path remotePath: remoteMods){
            Path modPath = Paths.get(downloadFolder.toString(), remotePath.getFileName().toString());

            if(!Files.exists(modPath)){
                Thread dThread = null;
                try {
                    Integer step = 0;
                    if(!Files.exists(downloadFolder))
                        Files.createDirectory(downloadFolder);

                    dThread = new Thread(()->{FTPService.getInstance().downloadMod(remotePath,downloadFolder.toString());});
                    dThread.start();

                    while(dThread.isAlive()){
                        StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) +"||Downloading mod file "+modPath.getFileName().toString()));
                        Thread.sleep(DOWNLOAD_STATUS_CHECK_MILLIS);
                    }

                }catch (IOException | InterruptedException ex){
                    StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept("Error downloading mod file "+ modPath.getFileName().toString()+"..."));
                    LOGGER.error(SCAN,"Error downloading mod file {}...",modPath.getFileName().toString());
                    LOGGER.error(ex.getMessage());
                    LOGGER.error(ex.getStackTrace());
                }
            }
        }

        LOGGER.debug(SCAN,"Validating mods");
        final List<ModFile> localMods = modsFolderLocator
                .scanMods()
                .stream()
                .peek(mf -> LOGGER.debug(SCAN,"Found mod file {} of type {} with locator {}", mf.getFileName(), mf.getType(), mf.getLocator()))
                .peek(mf -> StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept("Found mod file "+mf.getFileName()+" of type "+mf.getType())))
                .map(ModFile.class::cast)
                .collect(Collectors.toList());

        for (ModFile mod: localMods )
        {
            if(!remoteModNames.contains(mod.getFileName())){
                Path source = mod.getFilePath();
                Path target = Paths.get(mod.getFilePath().getParent().toString(),EXCLUDED_FOLDER, mod.getFileName()).toAbsolutePath();
                try{
                    if(!Files.exists(excludeFolder))
                        Files.createDirectory(excludeFolder);
                    Files.move(source,target);
                    LOGGER.debug(SCAN,"Moving mod {} to '{}' because is not longer necessary",mod.getFileName(),excludeFolder);
                    StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept("Moving mod "+mod.getFileName()+" to '"+EXCLUDED_FOLDER+"' because is not longer necessary"));
                }catch (IOException ex){
                    StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept("Error Moving mod "+mod.getFileName()+" to '"+EXCLUDED_FOLDER+"'"));
                    LOGGER.error(SCAN,"Error moving mod {} to '{}'",mod.getFileName(),excludeFolder);
                    LOGGER.error(ex.getMessage());
                    LOGGER.error(ex.getStackTrace());
                }
            }
        }

        List<Path> downloadedMods = null;
        try{
            downloadedMods = Files.list(downloadFolder)
                    .collect(Collectors.toList());
        }catch (IOException ex){
            LOGGER.error(SCAN,"Error moving getting the local mods");
            LOGGER.error(SCAN, ex.getMessage());
            downloadedMods= new ArrayList<>();
        }

        for(Path modDownloaded : downloadedMods){
            String name = modDownloaded.getFileName().toString();
            Path mod = Paths.get(modsFolderLocator.folder().toString(),name);
            if(remoteModNames.contains(name) && !Files.exists(mod)){
                try{
                    Files.copy(modDownloaded,mod);
                    StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept("Moving mod "+name+" from '"+DOWNLOAD_FOLDER+"' to the main mod folder because is required"));
                    LOGGER.debug(SCAN,"Moving mod {} from '{}' to the main mod folder because is required",name,DOWNLOAD_FOLDER);
                }catch (IOException ex){
                    StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept("Error Moving mod "+name+" from '"+DOWNLOAD_FOLDER+"' to the main mod folder"));
                    LOGGER.error(SCAN,"Error moving mod {} from '{}' to the main mod folder",name,DOWNLOAD_FOLDER);
                    LOGGER.error(ex.getMessage());
                    LOGGER.error(ex.getStackTrace());
                }
            }
        }

        return;
    }

    public BackgroundScanHandler discoverMods() {
        LOGGER.debug(SCAN,"Scanning for mods and other resources to load. We know {} ways to find mods", locatorList.size());
        final Map<IModFile.Type, List<ModFile>> modFiles = locatorList.stream()
                .peek(loc -> LOGGER.debug(SCAN,"Trying locator {}", loc))
                .map(IModLocator::scanMods)
                .flatMap(Collection::stream)
                .peek(mf -> LOGGER.debug(SCAN,"Found mod file {} of type {} with locator {}", mf.getFileName(), mf.getType(), mf.getLocator()))
                .peek(mf -> StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept("Found mod file "+mf.getFileName()+" of type "+mf.getType())))
                .map(ModFile.class::cast)
                .collect(Collectors.groupingBy(IModFile::getType));

        FMLLoader.getLanguageLoadingProvider().addAdditionalLanguages(modFiles.get(IModFile.Type.LANGPROVIDER));
        BackgroundScanHandler backgroundScanHandler = new BackgroundScanHandler(modFiles);
        final List<ModFile> mods = modFiles.getOrDefault(IModFile.Type.MOD, Collections.emptyList());
        final List<ModFile> brokenFiles = new ArrayList<>();
        for (Iterator<ModFile> iterator = mods.iterator(); iterator.hasNext(); )
        {
            ModFile mod = iterator.next();
            if (!mod.getLocator().isValid(mod) || !mod.identifyMods()) {
                LOGGER.warn(SCAN, "File {} has been ignored - it is invalid", mod.getFilePath());
                iterator.remove();
                brokenFiles.add(mod);
            }
        }
        LOGGER.debug(SCAN,"Found {} mod files with {} mods", mods::size, ()->mods.stream().mapToInt(mf -> mf.getModInfos().size()).sum());
        StartupMessageManager.modLoaderConsumer().ifPresent(c->c.accept("Found "+mods.size()+" modfiles to load"));
        final LoadingModList loadingModList = ModSorter.sort(mods);
        loadingModList.addCoreMods();
        loadingModList.addAccessTransformers();
        loadingModList.addForScanning(backgroundScanHandler);
        loadingModList.setBrokenFiles(brokenFiles);
        return backgroundScanHandler;
    }

    private static class LocatorClassLoader extends URLClassLoader {
        LocatorClassLoader() {
            super(Java9ClassLoaderUtil.getSystemClassPathURLs(), getSystemClassLoader());
        }

        @Override
        protected void addURL(final URL url) {
            super.addURL(url);
        }
    }
    private static class MinecraftLocator implements IModLocator {
        private final Path mcJar = FMLLoader.getMCPaths()[0];
        private final FileSystem fileSystem;

        MinecraftLocator() {
            if (!Files.isDirectory(mcJar)) {
                try {
                    fileSystem = FileSystems.newFileSystem(mcJar, getClass().getClassLoader());
                } catch (ZipError | IOException e) {
                    LOGGER.fatal(SCAN,"Invalid Minecraft JAR file - no filesystem created");
                    throw new RuntimeException(e);
                }
            } else {
                fileSystem = null;
            }
        }

        @Override
        public List<IModFile> scanMods() {
            return Collections.singletonList(ModFile.newFMLInstance(mcJar, this));
        }

        @Override
        public String name() {
            return "minecraft";
        }

        @Override
        public Path findPath(final IModFile modFile, final String... path) {
            if (path.length == 2 && Objects.equals(path[0], "META-INF")) {
                if (Objects.equals(path[1], "mods.toml")) {
                    final URI jarFileURI;
                    try {
                        jarFileURI = getClass().getClassLoader().getResource("minecraftmod.toml").toURI();
                        if (Objects.equals(jarFileURI.getScheme(), "jar")) {
                            // Initialize the filesystem for the forge jar, because otherwise this barfs?
                            FileSystems.newFileSystem(jarFileURI, new HashMap<>());
                        }
                    } catch (URISyntaxException | IOException e) {
                        LOGGER.fatal(SCAN, "Unable to read minecraft default mod");
                        throw new RuntimeException(e);
                    }
                    return Paths.get(jarFileURI);
                } else if (Objects.equals(path[1], "coremods.json")) {
                    return INVALID_PATH;
                }
            }
            if (Files.isDirectory(mcJar)) return findPathDirectory(modFile, path);
            return findPathJar(modFile, path);
        }

        private Path findPathDirectory(final IModFile modFile, final String... path) {
            if (path.length < 1) {
                throw new IllegalArgumentException("Missing path");
            }
            final Path target = Paths.get(path[0], Arrays.copyOfRange(path, 1, path.length));
            // try right path first (resources)
            return mcJar.resolve(target);
        }

        private Path findPathJar(final IModFile modFile, final String... path) {
            return fileSystem.getPath(path[0], Arrays.copyOfRange(path, 1, path.length));
        }
        @Override
        public void scanFile(final IModFile modFile, final Consumer<Path> pathConsumer) {
            LOGGER.debug(SCAN,"Scan started: {}", modFile);
            Path path;
            if (Files.isDirectory(mcJar))
                path = mcJar;
            else
                path = fileSystem.getPath("/");
            try (Stream<Path> files = Files.find(path, Integer.MAX_VALUE, (p, a) -> p.getNameCount() > 0 && p.getFileName().toString().endsWith(".class"))) {
                files.forEach(pathConsumer);
            } catch (IOException e) {
                e.printStackTrace();
            }
            LOGGER.debug(SCAN,"Scan finished: {}", modFile);
        }

        @Override
        public Pair<Optional<Manifest>, Optional<CodeSigner[]>> findManifestAndSigners(final Path file) {
            if (Files.isDirectory(mcJar)) {
                return Pair.of(Optional.empty(), Optional.empty());
            }
            try (JarFile jf = new JarFile(mcJar.toFile())) {
                final Manifest manifest = jf.getManifest();
                if (manifest!=null) {
                    final JarEntry jarEntry = jf.getJarEntry(JarFile.MANIFEST_NAME);
                    LamdbaExceptionUtils.uncheck(() -> AbstractJarFileLocator.ENSURE_INIT.invoke(jf));
                    return Pair.of(Optional.of(manifest), Optional.ofNullable(jarEntry.getCodeSigners()));
                }
            } catch (IOException ioe) {
                return Pair.of(Optional.empty(), Optional.empty());
            }
            return Pair.of(Optional.empty(), Optional.empty());
        }

        @Override
        public Optional<Manifest> findManifest(final Path file) {
            return Optional.empty();
        }

        @Override
        public void initArguments(final Map<String, ?> arguments) {
            // no op
        }

        @Override
        public boolean isValid(final IModFile modFile) {
            return true;
        }
    }
}
