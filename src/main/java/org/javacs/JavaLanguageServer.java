package org.javacs;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import io.typefox.lsapi.services.*;
import io.typefox.lsapi.*;
import io.typefox.lsapi.impl.*;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.xml.parsers.*;
import javax.xml.xpath.*;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import static org.javacs.Main.JSON;

class JavaLanguageServer implements LanguageServer {
    private static final Logger LOG = Logger.getLogger("main");
    private Path workspaceRoot;
    private Consumer<PublishDiagnosticsParams> publishDiagnostics = p -> {};
    private Consumer<MessageParams> showMessage = m -> {};
    private Map<Path, String> activeDocuments = new HashMap<>();

    public JavaLanguageServer() {
        this.testJavac = Optional.empty();
    }

    public JavaLanguageServer(JavacHolder testJavac) {
        this.testJavac = Optional.of(testJavac);
    }

    public void onError(String message, Throwable error) {
        if (error instanceof ShowMessageException)
            showMessage.accept(((ShowMessageException) error).message);
        else if (error instanceof NoJavaConfigException) {
            // Swallow error
            // If you want to show a message for no-java-config, 
            // you have to specifically catch the error lower down and re-throw it
            LOG.warning(error.getMessage());
        }
        else {
            LOG.log(Level.SEVERE, message, error);
            
            MessageParamsImpl m = new MessageParamsImpl();

            m.setMessage(message);
            m.setType(MessageType.Error);

            showMessage.accept(m);
        }
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        workspaceRoot = Paths.get(params.getRootPath()).toAbsolutePath().normalize();

        InitializeResultImpl result = new InitializeResultImpl();

        ServerCapabilitiesImpl c = new ServerCapabilitiesImpl();

        c.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        c.setDefinitionProvider(true);
        c.setCompletionProvider(new CompletionOptionsImpl());
        c.setHoverProvider(true);
        c.setWorkspaceSymbolProvider(true);
        c.setReferencesProvider(true);
        c.setDocumentSymbolProvider(true);

        result.setCapabilities(c);

        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void shutdown() {

    }

    @Override
    public void exit() {

    }

    @Override
    public void onTelemetryEvent(Consumer<Object> consumer) {
        // Nothing to do
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {
            @Override
            public CompletableFuture<CompletionList> completion(TextDocumentPositionParams position) {
                return CompletableFuture.completedFuture(autocomplete(position));
            }

            @Override
            public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
                return null;
            }

            @Override
            public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
                return CompletableFuture.completedFuture(doHover(position));
            }

            @Override
            public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends Location>> definition(TextDocumentPositionParams position) {
                return CompletableFuture.completedFuture(gotoDefinition(position));
            }

            @Override
            public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
                return CompletableFuture.completedFuture(findReferences(params));
            }

            @Override
            public CompletableFuture<DocumentHighlight> documentHighlight(TextDocumentPositionParams position) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(DocumentSymbolParams params) {
                return CompletableFuture.completedFuture(findDocumentSymbols(params));
            }

            @Override
            public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
                return null;
            }

            @Override
            public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
                return null;
            }

            @Override
            public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
                return null;
            }

            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
                try {
                    TextDocumentItem document = params.getTextDocument();
                    URI uri = URI.create(document.getUri());
                    Optional<Path> maybePath = getFilePath(uri);

                    maybePath.ifPresent(path -> {
                        String text = document.getText();

                        activeDocuments.put(path, text);

                        doLint(path);
                    });
                } catch (NoJavaConfigException e) {
                    throw ShowMessageException.warning(e.getMessage(), e);
                }
            }

            @Override
            public void didChange(DidChangeTextDocumentParams params) {
                VersionedTextDocumentIdentifier document = params.getTextDocument();
                URI uri = URI.create(document.getUri());
                Optional<Path> path = getFilePath(uri);

                if (path.isPresent()) {
                    for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
                        if (change.getRange() == null)
                            activeDocuments.put(path.get(), change.getText());
                        else {
                            String existingText = activeDocuments.get(path.get());
                            String newText = patch(existingText, change);

                            activeDocuments.put(path.get(), newText);
                        }
                    }
                }
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
                TextDocumentIdentifier document = params.getTextDocument();
                URI uri = URI.create(document.getUri());
                Optional<Path> path = getFilePath(uri);

                if (path.isPresent()) {
                    JavacHolder compiler = findCompiler(path.get());
                    JavaFileObject file = findFile(compiler, path.get());
                    
                    // Remove from source cache
                    activeDocuments.remove(path.get());
                }
            }

            @Override
            public void didSave(DidSaveTextDocumentParams params) {
                TextDocumentIdentifier document = params.getTextDocument();
                URI uri = URI.create(document.getUri());
                Optional<Path> maybePath = getFilePath(uri);

                // Re-lint all active documents
                // 
                // We would prefer to just re-lint the documents that the user can see
                // But there is no didSwitchTo(document) event, so we have no way of knowing when the user switches between tabs
                // Therefore, we just re-lint all open editors
                if (maybePath.isPresent()) 
                    doLint(activeDocuments.keySet());
            }

            @Override
            public void onPublishDiagnostics(Consumer<PublishDiagnosticsParams> callback) {
                publishDiagnostics = callback;
            }
        };
    }

    private String patch(String sourceText, TextDocumentContentChangeEvent change) {
        try {
            Range range = change.getRange();
            BufferedReader reader = new BufferedReader(new StringReader(sourceText));
            StringWriter writer = new StringWriter();

            // Skip unchanged lines
            int line = 0;

            while (line < range.getStart().getLine()) {
                writer.write(reader.readLine() + '\n');
                line++;
            }

            // Skip unchanged chars
            for (int character = 0; character < range.getStart().getCharacter(); character++)
                writer.write(reader.read());

            // Write replacement text
            writer.write(change.getText());

            // Skip replaced text
            reader.skip(change.getRangeLength());

            // Write remaining text
            while (true) {
                int next = reader.read();

                if (next == -1)
                    return writer.toString();
                else
                    writer.write(next);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<Path> getFilePath(URI uri) {
        if (!uri.getScheme().equals("file"))
            return Optional.empty();
        else
            return Optional.of(Paths.get(uri));
    }

    private void doLint(Path path) {
        doLint(Collections.singleton(path));
    }

    private void doLint(Collection<Path> paths) {
        LOG.info("Lint " + paths);

        DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();

        Map<JavacConfig, Set<JCTree.JCCompilationUnit>> parsedByConfig = new HashMap<>();

        // Parse all files and group them by compiler
        for (Path path : paths) {
            findConfig(path).ifPresent(config -> {
                Set<JCTree.JCCompilationUnit> collect = parsedByConfig.computeIfAbsent(config, newCompiler -> new HashSet<>());

                // Find the relevant compiler
                JavacHolder compiler = findCompilerForConfig(config);

                compiler.onError(errors);

                // Parse the file
                JavaFileObject file = findFile(compiler, path);
                JCTree.JCCompilationUnit parsed = compiler.parse(file);

                collect.add(parsed);
            });
        }


        for (JavacConfig config : parsedByConfig.keySet()) {
            Set<JCTree.JCCompilationUnit> parsed = parsedByConfig.get(config);
            JavacHolder compiler = findCompilerForConfig(config);
            SymbolIndex index = findIndexForConfig(config);

            compiler.compile(parsed);

            // TODO compiler should do this automatically
            for (JCTree.JCCompilationUnit compilationUnit : parsed) 
                index.update(compilationUnit, compiler.context);
        }

        publishDiagnostics(paths, errors);
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
                List<SymbolInformation> infos = indexCache.values()
                                                          .stream()
                                                          .flatMap(symbolIndex -> symbolIndex.search(params.getQuery()))
                                                          .limit(100)
                                                          .collect(Collectors.toList());

                return CompletableFuture.completedFuture(infos);
            }

            @Override
            public void didChangeConfiguraton(DidChangeConfigurationParams params) {
                
            }

            @Override
            public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
                for (FileEvent event : params.getChanges()) {
                    if (event.getUri().endsWith(".java")) {
                        if (event.getType() == FileChangeType.Deleted) {
                            URI uri = URI.create(event.getUri());

                            getFilePath(uri).ifPresent(path -> {
                                JavacHolder compiler = findCompiler(path);
                                JavaFileObject file = findFile(compiler, path);
                                SymbolIndex index = findIndex(path);

                                compiler.clear(file);
                                index.clear(file.toUri());
                            });
                        }
                    }
                    else if (event.getUri().endsWith("javaconfig.json")) {
                        // TODO invalidate caches when javaconfig.json changes
                    }
                }
            }
        };
    }

    @Override
    public WindowService getWindowService() {
        return new WindowService() {
            @Override
            public void onShowMessage(Consumer<MessageParams> callback) {
                showMessage = callback;
            }

            @Override
            public void onShowMessageRequest(Consumer<ShowMessageRequestParams> callback) {

            }

            @Override
            public void onLogMessage(Consumer<MessageParams> callback) {

            }
        };
    }
    
    private void publishDiagnostics(Collection<Path> paths, DiagnosticCollector<JavaFileObject> errors) {
        Map<URI, PublishDiagnosticsParamsImpl> files = new HashMap<>();
        
        paths.forEach(p -> files.put(p.toUri(), newPublishDiagnostics(p.toUri())));
        
        errors.getDiagnostics().forEach(error -> {
            if (error.getStartPosition() != javax.tools.Diagnostic.NOPOS) {
                URI uri = error.getSource().toUri();
                PublishDiagnosticsParamsImpl publish = files.computeIfAbsent(uri, this::newPublishDiagnostics);

                RangeImpl range = position(error);
                DiagnosticImpl diagnostic = new DiagnosticImpl();
                DiagnosticSeverity severity = severity(error.getKind());

                diagnostic.setSeverity(severity);
                diagnostic.setRange(range);
                diagnostic.setCode(error.getCode());
                diagnostic.setMessage(error.getMessage(null));

                publish.getDiagnostics().add(diagnostic);
            }
        });

        files.values().forEach(publishDiagnostics::accept);
    }

    private DiagnosticSeverity severity(javax.tools.Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return DiagnosticSeverity.Error;
            case WARNING:
            case MANDATORY_WARNING:
                return DiagnosticSeverity.Warning;
            case NOTE:
            case OTHER:
            default:
                return DiagnosticSeverity.Information;
        }
    }

    private PublishDiagnosticsParamsImpl newPublishDiagnostics(URI newUri) {
        PublishDiagnosticsParamsImpl p = new PublishDiagnosticsParamsImpl();

        p.setDiagnostics(new ArrayList<>());
        p.setUri(newUri.toString());

        return p;
    }

    private Map<JavacConfig, JavacHolder> compilerCache = new HashMap<>();

    /**
     * Instead of looking for javaconfig.json and creating a JavacHolder, just use this.
     * For testing.
     */
    private final Optional<JavacHolder> testJavac;

    /**
     * Look for a configuration in a parent directory of uri
     */
    private JavacHolder findCompiler(Path path) {
        if (testJavac.isPresent())
            return testJavac.get();

        Path dir = path.getParent();
        
        return findConfig(dir)
            .map(this::findCompilerForConfig)
            .orElseThrow(() -> new NoJavaConfigException(path));
    }

    private JavacHolder findCompilerForConfig(JavacConfig config) {
        if (testJavac.isPresent())
            return testJavac.get();
        else
            return compilerCache.computeIfAbsent(config, this::newJavac);
    }

    private JavacHolder newJavac(JavacConfig c) {
        return new JavacHolder(c.classPath,
                               c.sourcePath,
                               c.outputDirectory);
    }

    private Map<JavacConfig, SymbolIndex> indexCache = new HashMap<>();

    private SymbolIndex findIndex(Path path) {
        Path dir = path.getParent();
        Optional<JavacConfig> config = findConfig(dir);
        Optional<SymbolIndex> index = config.map(this::findIndexForConfig);

        return index.orElseThrow(() -> new NoJavaConfigException(path));
    }

    private SymbolIndex findIndexForConfig(JavacConfig config) {
        return indexCache.computeIfAbsent(config, this::newIndex);
    }

    private SymbolIndex newIndex(JavacConfig c) {
        return new SymbolIndex(c.classPath, c.sourcePath, c.outputDirectory);
    }

    // TODO invalidate cache when VSCode notifies us config file has changed
    private Map<Path, Optional<JavacConfig>> configCache = new HashMap<>();

    private Optional<JavacConfig> findConfig(Path dir) {
        return configCache.computeIfAbsent(dir, this::doFindConfig);
    }

    private Optional<JavacConfig> doFindConfig(Path dir) {
        if (testJavac.isPresent())
            return testJavac.map(j -> new JavacConfig(j.sourcePath, j.classPath, j.outputDirectory));

        while (true) {
            Optional<JavacConfig> found = readIfConfig(dir);

            if (found.isPresent())
                return found;
            else if (workspaceRoot.startsWith(dir))
                return Optional.empty();
            else
                dir = dir.getParent();
        }
    }

    /**
     * If directory contains a config file, for example javaconfig.json or an eclipse project file, read it.
     */
    private Optional<JavacConfig> readIfConfig(Path dir) {
        if (Files.exists(dir.resolve("javaconfig.json"))) {
            JavaConfigJson json = readJavaConfigJson(dir.resolve("javaconfig.json"));
            Set<Path> classPath = json.classPathFile.map(classPathFile -> {
                Path classPathFilePath = dir.resolve(classPathFile);
                return readClassPathFile(classPathFilePath);
            }).orElse(Collections.emptySet());
            Set<Path> sourcePath = json.sourcePath.stream().map(dir::resolve).collect(Collectors.toSet());
            Path outputDirectory = dir.resolve(json.outputDirectory);
            JavacConfig config = new JavacConfig(sourcePath, classPath, outputDirectory);

            return Optional.of(config);
        }
        else if (Files.exists(dir.resolve("pom.xml"))) {
            Path pomXml = dir.resolve("pom.xml");

            // Invoke maven to get classpath
            Set<Path> classPath = buildClassPath(pomXml);

            // Get source directory from pom.xml
            Set<Path> sourcePath = sourceDirectories(pomXml);

            // Use target/javacs
            Path outputDirectory = Paths.get("target/javacs").toAbsolutePath();

            JavacConfig config = new JavacConfig(sourcePath, classPath, outputDirectory);

            return Optional.of(config);
        }
        // TODO add more file types
        else {
            return Optional.empty();
        }
    }

    public static Set<Path> buildClassPath(Path pomXml) {
        try {
            Objects.requireNonNull(pomXml, "pom.xml path is null");

            // Tell maven to output classpath to a temporary file
            // TODO if pom.xml already specifies outputFile, use that location
            Path classPathTxt = Files.createTempFile("classpath", ".txt");

            LOG.info("Emit classpath to " + classPathTxt);

            String cmd = getMvnCommand() + " dependency:build-classpath -Dmdep.outputFile=" + classPathTxt;
            File workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            int result = Runtime.getRuntime().exec(cmd, null, workingDirectory).waitFor();

            if (result != 0)
                throw new RuntimeException("`" + cmd + "` returned " + result);

            return readClassPathFile(classPathTxt);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getMvnCommand() {
        String mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd");
            if (mvnCommand == null) {
                mvnCommand = findExecutableOnPath("mvn.bat");
            }
        }
        return mvnCommand;
    }

    private static String findExecutableOnPath(String name) {
        for (String dirname : System.getenv("PATH").split(File.pathSeparator)) {
            File file = new File(dirname, name);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private static Set<Path> sourceDirectories(Path pomXml) {
        try {
            Set<Path> all = new HashSet<>();

            // Parse pom.xml
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomXml.toFile());

            // Find source directory
            String sourceDir = XPathFactory.newInstance().newXPath().compile("/project/build/sourceDirectory").evaluate(doc);

            if (sourceDir == null || sourceDir.isEmpty()) {
                LOG.info("Use default source directory src/main/java");

                sourceDir = "src/main/java";
            }
            else LOG.info("Use source directory from pom.xml " + sourceDir);
            
            all.add(pomXml.resolveSibling(sourceDir).toAbsolutePath());

            // Find test directory
            String testDir = XPathFactory.newInstance().newXPath().compile("/project/build/testSourceDirectory").evaluate(doc);

            if (testDir == null || testDir.isEmpty()) {
                LOG.info("Use default test directory src/test/java");

                testDir = "src/test/java";
            }
            else LOG.info("Use test directory from pom.xml " + testDir);
            
            all.add(pomXml.resolveSibling(testDir).toAbsolutePath());

            return all;
        } catch (IOException | ParserConfigurationException | SAXException | XPathExpressionException e) {
            throw new RuntimeException(e);
        }
    }

    private JavaConfigJson readJavaConfigJson(Path configFile) {
        try {
            return JSON.readValue(configFile.toFile(), JavaConfigJson.class);
        } catch (IOException e) {
            MessageParamsImpl message = new MessageParamsImpl();

            message.setMessage("Error reading " + configFile);
            message.setType(MessageType.Error);

            throw new ShowMessageException(message, e);
        }
    }

    private static Set<Path> readClassPathFile(Path classPathFilePath) {
        try {
            InputStream in = Files.newInputStream(classPathFilePath);
            String text = new BufferedReader(new InputStreamReader(in))
                    .lines()
                    .collect(Collectors.joining());
            Path dir = classPathFilePath.getParent();

            return Arrays.stream(text.split(File.pathSeparator))
                         .map(dir::resolve)
                         .collect(Collectors.toSet());
        } catch (IOException e) {
            MessageParamsImpl message = new MessageParamsImpl();

            message.setMessage("Error reading " + classPathFilePath);
            message.setType(MessageType.Error);

            throw new ShowMessageException(message, e);
        }
    }

    private JavaFileObject findFile(JavacHolder compiler, Path path) {
        if (activeDocuments.containsKey(path))
            return new StringFileObject(activeDocuments.get(path), path);
        else
            return compiler.fileManager.getRegularFile(path.toFile());
    }

    private RangeImpl position(javax.tools.Diagnostic<? extends JavaFileObject> error) {
        // Compute start position
        PositionImpl start = new PositionImpl();

        start.setLine((int) (error.getLineNumber() - 1));
        start.setCharacter((int) (error.getColumnNumber() - 1));

        // Compute end position
        PositionImpl end = endPosition(error);

        // Combine into Range
        RangeImpl range = new RangeImpl();

        range.setStart(start);
        range.setEnd(end);

        return range;
    }

    private PositionImpl endPosition(javax.tools.Diagnostic<? extends JavaFileObject> error) {
        try (Reader reader = error.getSource().openReader(true)) {
            long startOffset = error.getStartPosition();
            long endOffset = error.getEndPosition();

            reader.skip(startOffset);

            int line = (int) error.getLineNumber() - 1;
            int column = (int) error.getColumnNumber() - 1;

            for (long i = startOffset; i < endOffset; i++) {
                int next = reader.read();

                if (next == '\n') {
                    line++;
                    column = 0;
                }
                else
                    column++;
            }

            PositionImpl end = new PositionImpl();

            end.setLine(line);
            end.setCharacter(column);

            return end;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<? extends Location> findReferences(ReferenceParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());
        int line = params.getPosition().getLine();
        int character = params.getPosition().getCharacter();
        List<Location> result = new ArrayList<>();

        getFilePath(uri).ifPresent(path -> {
            JCTree.JCCompilationUnit compilationUnit = findTree(path);

            findSymbol(compilationUnit, line, character).ifPresent(symbol -> {
                if (SymbolIndex.shouldIndex(symbol)) {
                    SymbolIndex index = findIndex(path);

                    index.references(symbol).forEach(result::add);
                }
                else {
                    compilationUnit.accept(new TreeScanner() {
                        @Override
                        public void visitSelect(JCTree.JCFieldAccess tree) {
                            super.visitSelect(tree);

                            if (tree.sym != null && tree.sym.equals(symbol))
                                result.add(SymbolIndex.location(tree, compilationUnit));
                        }

                        @Override
                        public void visitReference(JCTree.JCMemberReference tree) {
                            super.visitReference(tree);

                            if (tree.sym != null && tree.sym.equals(symbol))
                                result.add(SymbolIndex.location(tree, compilationUnit));
                        }

                        @Override
                        public void visitIdent(JCTree.JCIdent tree) {
                            super.visitIdent(tree);

                            if (tree.sym != null && tree.sym.equals(symbol))
                                result.add(SymbolIndex.location(tree, compilationUnit));
                        }
                    });
                }
            });
        });

        return result;
    }

    private List<? extends SymbolInformation> findDocumentSymbols(DocumentSymbolParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());

        return getFilePath(uri).map(path -> {
            SymbolIndex index = findIndex(path);
            List<? extends SymbolInformation> found = index.allInFile(uri).collect(Collectors.toList());

            return found;
        }).orElse(Collections.emptyList());
    }

    private JCTree.JCCompilationUnit findTree(Path path) {
        JavacHolder compiler = findCompiler(path);
        SymbolIndex index = findIndex(path);
        JavaFileObject file = findFile(compiler, path);

        compiler.onError(err -> {});

        JCTree.JCCompilationUnit tree = compiler.parse(file);

        compiler.compile(Collections.singleton(tree));

        // TODO compiler should do this automatically
        index.update(tree, compiler.context);

        return tree;
    }

    public Optional<Symbol> findSymbol(JCTree.JCCompilationUnit tree, int line, int character) {
        JavaFileObject file = tree.getSourceFile();

        return getFilePath(file.toUri()).flatMap(path -> {
            JavacHolder compiler = findCompiler(path);
            long cursor = findOffset(file, line, character);
            SymbolUnderCursorVisitor visitor = new SymbolUnderCursorVisitor(file, cursor, compiler.context);

            tree.accept(visitor);

            return visitor.found;
        });
    }

    public List<? extends Location> gotoDefinition(TextDocumentPositionParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        int line = position.getPosition().getLine();
        int character = position.getPosition().getCharacter();
        List<Location> result = new ArrayList<>();

        getFilePath(uri).ifPresent(path -> {
            JCTree.JCCompilationUnit compilationUnit = findTree(path);

            findSymbol(compilationUnit, line, character).ifPresent(symbol -> {
                if (SymbolIndex.shouldIndex(symbol)) {
                    SymbolIndex index = findIndex(path);

                    index.findSymbol(symbol).ifPresent(info -> {
                        result.add(info.getLocation());
                    });
                }
                else {
                    JCTree symbolTree = TreeInfo.declarationFor(symbol, compilationUnit);

                    if (symbolTree != null)
                        result.add(SymbolIndex.location(symbolTree, compilationUnit));
                }
            });
        });

        return result;
    }

    /**
     * Convert on offset-based range to a {@link io.typefox.lsapi.Range}
     */
    public static RangeImpl findPosition(JavaFileObject file, long startOffset, long endOffset) {
        try (Reader in = file.openReader(true)) {
            long offset = 0;
            int line = 0;
            int character = 0;

            // Find the start position
            while (offset < startOffset) {
                int next = in.read();

                if (next < 0)
                    break;
                else {
                    offset++;
                    character++;

                    if (next == '\n') {
                        line++;
                        character = 0;
                    }
                }
            }

            PositionImpl start = createPosition(line, character);

            // Find the end position
            while (offset < endOffset) {
                int next = in.read();

                if (next < 0)
                    break;
                else {
                    offset++;
                    character++;

                    if (next == '\n') {
                        line++;
                        character = 0;
                    }
                }
            }

            PositionImpl end = createPosition(line, character);

            // Combine into range
            RangeImpl range = new RangeImpl();

            range.setStart(start);
            range.setEnd(end);

            return range;
        } catch (IOException e) {
            throw ShowMessageException.error(e.getMessage(), e);
        }
    }

    private static PositionImpl createPosition(int line, int character) {
        PositionImpl p = new PositionImpl();

        p.setLine(line);
        p.setCharacter(character);

        return p;
    }

    private static long findOffset(JavaFileObject file, int targetLine, int targetCharacter) {
        try (Reader in = file.openReader(true)) {
            long offset = 0;
            int line = 0;
            int character = 0;

            while (line < targetLine) {
                int next = in.read();

                if (next < 0)
                    return offset;
                else {
                    offset++;

                    if (next == '\n')
                        line++;
                }
            }

            while (character < targetCharacter) {
                int next = in.read();

                if (next < 0)
                    return offset;
                else {
                    offset++;
                    character++;
                }
            }

            return offset;
        } catch (IOException e) {
            throw ShowMessageException.error(e.getMessage(), e);
        }
    }
    
    private HoverImpl doHover(TextDocumentPositionParams position) {
        HoverImpl result = new HoverImpl();
        List<MarkedStringImpl> contents = new ArrayList<>();

        result.setContents(contents);

        URI uri = URI.create(position.getTextDocument().getUri());
        int line = position.getPosition().getLine();
        int character = position.getPosition().getCharacter();

        getFilePath(uri).ifPresent(path -> {
            JCTree.JCCompilationUnit compilationUnit = findTree(path);

            findSymbol(compilationUnit, line, character).ifPresent(symbol -> {
                switch (symbol.getKind()) {
                    case PACKAGE:
                        contents.add(markedString("package " + symbol.getQualifiedName()));

                        break;
                    case ENUM:
                        contents.add(markedString("enum " + symbol.getQualifiedName()));

                        break;
                    case CLASS:
                        contents.add(markedString("class " + symbol.getQualifiedName()));

                        break;
                    case ANNOTATION_TYPE:
                        contents.add(markedString("@interface " + symbol.getQualifiedName()));

                        break;
                    case INTERFACE:
                        contents.add(markedString("interface " + symbol.getQualifiedName()));

                        break;
                    case METHOD:
                    case CONSTRUCTOR:
                    case STATIC_INIT:
                    case INSTANCE_INIT:
                        Symbol.MethodSymbol method = (Symbol.MethodSymbol) symbol;
                        String signature = AutocompleteVisitor.methodSignature(method);
                        String returnType = ShortTypePrinter.print(method.getReturnType());

                        contents.add(markedString(returnType + " " + signature));

                        break;
                    case PARAMETER:
                    case LOCAL_VARIABLE:
                    case EXCEPTION_PARAMETER:
                    case ENUM_CONSTANT:
                    case FIELD:
                        contents.add(markedString(ShortTypePrinter.print(symbol.type)));

                        break;
                    case TYPE_PARAMETER:
                    case OTHER:
                    case RESOURCE_VARIABLE:
                        break;
                }
            });
        });

        return result;
    }

    private MarkedStringImpl markedString(String value) {
        MarkedStringImpl result = new MarkedStringImpl();

        result.setLanguage("java");
        result.setValue(value);

        return result;
    }

    public CompletionList autocomplete(TextDocumentPositionParams position) {
        CompletionListImpl result = new CompletionListImpl();

        result.setIncomplete(false);
        result.setItems(new ArrayList<>());

        Optional<Path> maybePath = getFilePath(URI.create(position.getTextDocument().getUri()));

        if (maybePath.isPresent()) {
            Path path = maybePath.get();
            DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();
            JavacHolder compiler = findCompiler(path);
            JavaFileObject file = findFile(compiler, path);
            long cursor = findOffset(file, position.getPosition().getLine(), position.getPosition().getCharacter());
            JavaFileObject withSemi = withSemicolonAfterCursor(file, path, cursor);
            AutocompleteVisitor autocompleter = new AutocompleteVisitor(withSemi, cursor, compiler.context);

            compiler.onError(errors);

            JCTree.JCCompilationUnit ast = compiler.parse(withSemi);

            // Remove all statements after the cursor
            // There are often parse errors after the cursor, which can generate unrecoverable type errors
            ast.accept(new AutocompletePruner(withSemi, cursor, compiler.context));

            compiler.compile(Collections.singleton(ast));

            ast.accept(autocompleter);

            result.getItems().addAll(autocompleter.suggestions);
        }

        return result;
    }

    /**
     * Insert ';' after the users cursor so we recover from parse errors in a helpful way when doing autocomplete.
     */
    private JavaFileObject withSemicolonAfterCursor(JavaFileObject file, Path path, long cursor) {
        try (Reader reader = file.openReader(true)) {
            StringBuilder acc = new StringBuilder();

            for (int i = 0; i < cursor; i++) {
                int next = reader.read();

                if (next == -1)
                    throw new RuntimeException("End of file " + file + " before cursor " + cursor);

                acc.append((char) next);
            }

            acc.append(";");

            for (int next = reader.read(); next > 0; next = reader.read()) {
                acc.append((char) next);
            }

            return new StringFileObject(acc.toString(), path);
        } catch (IOException e) {
            throw ShowMessageException.error("Error reading " + file, e);
        }
    }
}
