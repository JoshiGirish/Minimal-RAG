import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A Quarkus-compatible class for parsing documents using Apache Tika.
 * Manages the parsing of all documents in a given directory.
 */
public class TikaDocumentParser {

    private static final Logger LOG = Logger.getLogger(TikaDocumentParser.class.getName());
    private static final String DEFAULT_TIKA_CONFIG = "tika-defaults.xml";
    private static final int DEFAULT_CONTENT_LENGTH = 1024 * 1024 * 10; // 10MB default

    private final TikaConfig tikaConfig;
    private final Parser parser;
    private final int contentLength;

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".pdf",
            ".doc",
            ".docx",
            ".ppt",
            ".pptx",
            ".xls",
            ".xlsx",
            ".rtf",
            ".odt",
            ".odp",
            ".ods",
            ".txt",
            ".md",
            ".html",
            ".htm",
            ".xml",
            ".json",
            ".csv",
            ".yaml",
            ".yml",
            ".log",
            ".ini",
            ".conf",
            ".cfg",
            ".properties",
            ".tex",
            ".epub",
            ".fb2",
            ".azw3",
            ".mobi",
            ".java");

    /**
     * Constructor with default Tika configuration.
     * 
     * @throws SAXException
     * @throws IOException
     * @throws TikaException
     */
    public TikaDocumentParser() throws TikaException, IOException, SAXException {
        this(null, DEFAULT_CONTENT_LENGTH);
    }

    /**
     * Constructor with custom Tika configuration.
     *
     * @param tikaConfigPath Path to custom Tika configuration file (null for
     *                       default)
     * @param contentLength  Maximum content length to parse (in bytes)
     * @throws SAXException
     * @throws IOException
     * @throws TikaException
     */
    public TikaDocumentParser(String tikaConfigPath, int contentLength)
            throws TikaException, IOException, SAXException {
        // this.tikaConfig = tikaConfigPath != null ?
        // new TikaConfig(tikaConfigPath) :
        // new TikaConfig(DEFAULT_TIKA_CONFIG);
        this.tikaConfig = new TikaConfig();
        this.parser = new AutoDetectParser(tikaConfig);
        this.contentLength = contentLength;
    }

    /**
     * Parse all documents in the given directory.
     *
     * @param directoryPath Path to the directory containing documents
     * @return List of parsed document results
     * @throws IOException If an I/O error occurs
     */
    public List<DocumentParseResult> parseDirectory(String directoryPath) throws IOException {
        List<DocumentParseResult> results = new ArrayList<>();
        Path dir = Paths.get(directoryPath);

        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Path does not exist or is not a directory: " + directoryPath);
        }

        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isDocumentFile(file)) {
                    try {
                        DocumentParseResult result = parseDocument(file.toAbsolutePath().toString());
                        results.add(result);
                    } catch (Exception e) {
                        LOG.warning("Failed to parse file: " + file + " - " + e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOG.warning("Failed to visit file: " + file + " - " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        return results;
    }

    /**
     * Parse a single document.
     *
     * @param filePath Path to the document
     * @return DocumentParseResult containing parsed content and metadata
     * @throws IOException   If an I/O error occurs
     * @throws TikaException
     * @throws SAXException
     */
    public DocumentParseResult parseDocument(String filePath) throws IOException, SAXException, TikaException {
        Path file = Paths.get(filePath);
        if (!Files.exists(file)) {
            throw new FileNotFoundException("File not found: " + filePath);
        }

        try (InputStream inputStream = Files.newInputStream(file)) {
            Metadata metadata = new Metadata();
            metadata.set("tika:content-length", String.valueOf(contentLength));

            ContentHandler handler = new BodyContentHandler(contentLength);
            ParseContext context = new ParseContext();
            // context.setMetadata(metadata);

            parser.parse(inputStream, handler, metadata, context);

            String content = handler.toString();
            String mimeType = metadata.get("Content-Type");
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            return new DocumentParseResult(
                    file.getFileName().toString(),
                    mimeType,
                    content,
                    metadata);
        }
    }

    /**
     * Parse a single document with a custom content length.
     *
     * @param filePath Path to the document
     * @param length   Maximum content length to parse
     * @return DocumentParseResult containing parsed content and metadata
     * @throws IOException   If an I/O error occurs
     * @throws TikaException
     * @throws SAXException
     */
    public DocumentParseResult parseDocument(String filePath, int length)
            throws IOException, SAXException, TikaException {
        return parseDocument(filePath, length, null);
    }

    /**
     * Parse a single document with custom content length and metadata.
     *
     * @param filePath Path to the document
     * @param length   Maximum content length to parse
     * @param metadata Custom metadata to set
     * @return DocumentParseResult containing parsed content and metadata
     * @throws IOException   If an I/O error occurs
     * @throws TikaException
     * @throws SAXException
     */
    public DocumentParseResult parseDocument(String filePath, int length, Map<String, String> metadata)
            throws IOException, SAXException, TikaException {
        Path file = Paths.get(filePath);
        if (!Files.exists(file)) {
            throw new FileNotFoundException("File not found: " + filePath);
        }

        try (InputStream inputStream = Files.newInputStream(file)) {
            Metadata meta = new Metadata();
            if (metadata != null) {
                metadata.forEach(meta::set);
            }
            meta.set("tika:content-length", String.valueOf(length));

            ContentHandler handler = new BodyContentHandler(length);
            ParseContext context = new ParseContext();

            parser.parse(inputStream, handler, meta, context);

            String content = handler.toString();
            String mimeType = meta.get("Content-Type");
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            return new DocumentParseResult(
                    file.getFileName().toString(),
                    mimeType,
                    content,
                    meta);
        }
    }

    /**
     * Check if a file is a document type that should be parsed.
     *
     * @param file Path to the file
     * @return true if the file is a document type
     */
    private boolean isDocumentFile(Path file) {

        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }

        String fileName = file.getFileName()
                .toString()
                .toLowerCase();

        // Fast extension-based check
        boolean extensionMatch = DOCUMENT_EXTENSIONS.stream()
                .anyMatch(fileName::endsWith);

        if (extensionMatch) {
            return true;
        }

        // MIME-based fallback
        try (InputStream is = Files.newInputStream(file)) {

            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

            Detector detector = tikaConfig.getDetector();

            MediaType mediaType = detector.detect(is, metadata);

            if (mediaType == null) {
                return false;
            }

            String mimeType = mediaType.toString();

            return mimeType.startsWith("text/")
                    || mimeType.equals("application/pdf")
                    || mimeType.equals("application/json")
                    || mimeType.equals("application/xml")
                    || mimeType.equals("application/rtf")
                    || mimeType.contains("word")
                    || mimeType.contains("presentation")
                    || mimeType.contains("spreadsheet")
                    || mimeType.contains("officedocument");

        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get the Tika configuration being used.
     *
     * @return The TikaConfig instance
     */
    public TikaConfig getTikaConfig() {
        return tikaConfig;
    }

    /**
     * Get the parser being used.
     *
     * @return The Parser instance
     */
    public Parser getParser() {
        return parser;
    }

    /**
     * Get the content length limit.
     *
     * @return The content length limit
     */
    public int getContentLength() {
        return contentLength;
    }

    /**
     * Close resources associated with this parser.
     */
    public void close() {
        // Apache Tika resources are typically managed by the JVM
        // No explicit cleanup needed for most cases
    }

    /**
     * Result of parsing a single document.
     */
    public static class DocumentParseResult {
        private final String fileName;
        private final String mimeType;
        private final String content;
        private final Metadata metadata;

        public DocumentParseResult(String fileName, String mimeType, String content, Metadata metadata) {
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.content = content;
            this.metadata = metadata;
        }

        public String getFileName() {
            return fileName;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getContent() {
            return content;
        }

        public Metadata getMetadata() {
            return metadata;
        }

        @Override
        public String toString() {
            return "DocumentParseResult{" +
                    "fileName='" + fileName + '\'' +
                    ", mimeType='" + mimeType + '\'' +
                    ", contentLength=" + (content != null ? content.length() : 0) +
                    '}';
        }
    }
}