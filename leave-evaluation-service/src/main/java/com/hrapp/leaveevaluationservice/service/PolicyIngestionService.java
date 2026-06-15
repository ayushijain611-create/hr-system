package com.hrapp.leaveevaluationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class PolicyIngestionService {

    public static final String METADATA_SOURCE_KEY = "source";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final String vectorStoreTable;

    // Wires VectorStore (portable API) and JdbcTemplate (used for list/count queries the VectorStore API doesn't expose).
    public PolicyIngestionService(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
            String vectorStoreTable) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStoreTable = vectorStoreTable;
    }

    // Idempotently ingests a PDF: deletes any prior chunks for the same filename, then parses, chunks, embeds, and stores it.
    public IngestResult ingest(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Uploaded file must have a name");
        }
        if (!filename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported, got: " + filename);
        }
        log.info("Ingesting policy document: {} ({} bytes)", filename, file.getSize());

        long deletedPrior = deleteBySource(filename);

        Resource resource = toNamedResource(file, filename);
        List<Document> pageDocs = readPdf(resource);
        log.info("Extracted {} page(s) from {}", pageDocs.size(), filename);

        // Smaller chunks (~250 tokens) give the retriever finer-grained
        // matches than the default 800-token chunks. This keeps the prompt
        // smaller at query time, which is the main lever for LLM latency.
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(250)
                .withMinChunkSizeChars(120)
                .withMinChunkLengthToEmbed(10)
                .withMaxNumChunks(5000)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(pageDocs);
        log.info("Split into {} chunk(s) for {}", chunks.size(), filename);

        List<Document> tagged = chunks.stream()
                .map(d -> withSource(d, filename))
                .toList();

        vectorStore.add(tagged);
        log.info("Stored {} chunks for {} (replaced {} prior chunks)",
                tagged.size(), filename, deletedPrior);

        return new IngestResult(filename, tagged.size(), deletedPrior);
    }

    // Removes all chunks tagged with the given source filename and returns how many were deleted.
    public long deleteBySource(String source) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + vectorStoreTable
                        + " WHERE metadata->>'source' = ?",
                Integer.class, source);
        long prior = count == null ? 0 : count;
        if (prior > 0) {
            Filter.Expression expr = new FilterExpressionBuilder()
                    .eq(METADATA_SOURCE_KEY, source)
                    .build();
            vectorStore.delete(expr);
            log.info("Deleted {} chunks for source '{}'", prior, source);
        }
        return prior;
    }

    // Lists every distinct source filename currently in the vector store along with its chunk count.
    public List<PolicySummary> listSources() {
        return jdbcTemplate.query(
                "SELECT metadata->>'source' AS source, COUNT(*) AS chunks "
                        + "FROM " + vectorStoreTable + " "
                        + "WHERE metadata->>'source' IS NOT NULL "
                        + "GROUP BY metadata->>'source' "
                        + "ORDER BY source",
                (rs, i) -> new PolicySummary(rs.getString("source"), rs.getInt("chunks"))
        );
    }

    // Parses the PDF into one Document per page using Apache PDFBox via Spring AI's PagePdfDocumentReader.
    private List<Document> readPdf(Resource resource) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageTopMargin(0)
                .withPagesPerDocument(1)
                .withPageExtractedTextFormatter(
                        ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(0)
                                .build())
                .build();
        return new PagePdfDocumentReader(resource, config).read();
    }

    // Wraps the uploaded bytes in a Resource that reports the original filename (PagePdfDocumentReader requires a named Resource).
    private Resource toNamedResource(MultipartFile file, String filename) {
        try {
            return new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded file: " + filename, e);
        }
    }

    // Returns a copy of the Document with the source filename added to metadata (used later for filtering and listing).
    private Document withSource(Document doc, String filename) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put(METADATA_SOURCE_KEY, filename);
        return Document.builder()
                .text(doc.getText())
                .metadata(meta)
                .build();
    }

    public record IngestResult(String source, int chunks, long replacedPriorChunks) {}

    public record PolicySummary(String source, int chunks) {}
}
