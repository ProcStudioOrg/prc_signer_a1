package com.example.documentsigner.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.documentsigner.support.SigningFixtures;
import com.example.documentsigner.support.SigningFixtures.Widget;
import com.example.documentsigner.usage.UsageTracker;

/**
 * A posição da assinatura pela porta HTTP: o que a API aceita, o que recusa
 * com 400 legível, e como o lote aplica posição por arquivo.
 */
class SignPdfPlacementApiTest {

    @TempDir
    Path tempDir;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        UsageTracker tracker = new UsageTracker(tempDir.resolve("usage.sqlite3").toString(), "sal");
        tracker.init();
        mvc = MockMvcBuilders
            .standaloneSetup(new SignerController(new SigningService(), tracker))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private MockMultipartHttpServletRequestBuilder signPdf(int pages) throws Exception {
        return signPdf(pages, true);
    }

    private MockMultipartHttpServletRequestBuilder signPdf(int pages, boolean visible) throws Exception {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/sign/pdf")
            .file(new MockMultipartFile("document", "contrato.pdf", "application/pdf", SigningFixtures.pdf(pages)))
            .file(new MockMultipartFile("certificate", "teste.pfx", "application/x-pkcs12", SigningFixtures.pkcs12()))
            .param("password", SigningFixtures.PASSWORD)
            .param("visible", String.valueOf(visible));
    }

    private MockMultipartHttpServletRequestBuilder signBatch(int... pagesPerDocument) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/sign/pdf/batch");
        for (int i = 0; i < pagesPerDocument.length; i++) {
            request.file(new MockMultipartFile("documents", "doc" + (i + 1) + ".pdf", "application/pdf",
                SigningFixtures.pdf(pagesPerDocument[i])));
        }
        return (MockMultipartHttpServletRequestBuilder) request
            .file(new MockMultipartFile("certificate", "teste.pfx", "application/x-pkcs12", SigningFixtures.pkcs12()))
            .param("password", SigningFixtures.PASSWORD)
            .param("visible", "true");
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }

    // ---------- /sign/pdf ----------

    @Test
    void assinaNaPosicaoPedidaAceitandoDecimais() throws Exception {
        byte[] signed = mvc.perform(signPdf(3).param("page", "2").param("x", "100.5").param("y", "200")
                .param("width", "300").param("height", "127.5"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();

        Widget widget = SigningFixtures.signatureWidget(signed);
        assertEquals(1, widget.pageIndex);
        assertEquals(100.5, widget.rect.getLowerLeftX(), 0.01);
        assertEquals(200, widget.rect.getLowerLeftY(), 0.01);
        assertEquals(300, widget.rect.getWidth(), 0.01);
        assertEquals(127.5, widget.rect.getHeight(), 0.01);
    }

    @Test
    void semCoordenadasContinuaUsandoOAtalhoNaUltimaPagina() throws Exception {
        byte[] signed = mvc.perform(signPdf(2).param("position", "bottom-left"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();

        Widget widget = SigningFixtures.signatureWidget(signed);
        assertEquals(1, widget.pageIndex);
        assertEquals(20, widget.rect.getLowerLeftX(), 0.01);
        assertEquals(20, widget.rect.getLowerLeftY(), 0.01);
    }

    @Test
    void caixaForaDaPaginaDevolve400ComMotivo() throws Exception {
        mvc.perform(signPdf(1).param("page", "1").param("x", "500").param("y", "100"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE_PLACEMENT"))
            .andExpect(jsonPath("$.error").value(
                "Signature box (x=500, y=100, width=240, height=102) does not fit page 1, which is "
                + "595.28 x 841.89 pt. Origin is the bottom-left corner of the visible page."));
    }

    @Test
    void paginaInexistenteDevolve400() throws Exception {
        mvc.perform(signPdf(2).param("page", "7"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE_PAGE"))
            .andExpect(jsonPath("$.error").value("Invalid page number: 7. Document has 2 pages."));
    }

    @Test
    void coordenadaQueNaoEhNumeroDevolve400() throws Exception {
        mvc.perform(signPdf(1).param("x", "abc").param("y", "10"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.error").value("Invalid value for parameter: x"));
    }

    @Test
    void coordenadaNaNDevolve400() throws Exception {
        mvc.perform(signPdf(1).param("x", "NaN").param("y", "10"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE_PLACEMENT"));
    }

    @Test
    void atalhoDesconhecidoDevolve400EmVezDeCairNoCantoPadrao() throws Exception {
        mvc.perform(signPdf(1).param("position", "center"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE_PLACEMENT"));
    }

    @Test
    void assinaturaInvisivelIgnoraPosicao() throws Exception {
        mvc.perform(signPdf(1, false).param("x", "9999").param("y", "9999"))
            .andExpect(status().isOk());
    }

    // ---------- /sign/pdf/batch ----------

    @Test
    void loteAplicaPosicaoPorArquivoEHerdaADaRequisicaoNoItemNull() throws Exception {
        String placements = "[{\"page\":1,\"x\":40,\"y\":60},null,{\"page\":2,\"x\":300,\"y\":500,\"width\":120,\"height\":51}]";

        byte[] zip = mvc.perform(signBatch(2, 2, 3).param("position", "top-left").param("page", "1")
                .param("placements", placements))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Signed-Count", "3"))
            .andExpect(header().string("X-Failed-Count", "0"))
            .andReturn().getResponse().getContentAsByteArray();

        Map<String, byte[]> files = unzip(zip);
        Widget first = SigningFixtures.signatureWidget(files.get("doc1_signed.pdf"));
        assertEquals(0, first.pageIndex);
        assertEquals(40, first.rect.getLowerLeftX(), 0.01);
        assertEquals(60, first.rect.getLowerLeftY(), 0.01);

        Widget second = SigningFixtures.signatureWidget(files.get("doc2_signed.pdf"));
        assertEquals(0, second.pageIndex);
        assertEquals(20, second.rect.getLowerLeftX(), 0.01);
        assertEquals(841.89 - 122, second.rect.getLowerLeftY(), 0.01);

        Widget third = SigningFixtures.signatureWidget(files.get("doc3_signed.pdf"));
        assertEquals(1, third.pageIndex);
        assertEquals(300, third.rect.getLowerLeftX(), 0.01);
        assertEquals(120, third.rect.getWidth(), 0.01);
    }

    @Test
    void loteSemPlacementsUsaAMesmaPosicaoEmTodos() throws Exception {
        byte[] zip = mvc.perform(signBatch(1, 2).param("page", "1").param("x", "50").param("y", "70"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Signed-Count", "2"))
            .andReturn().getResponse().getContentAsByteArray();

        for (byte[] signed : unzip(zip).values()) {
            Widget widget = SigningFixtures.signatureWidget(signed);
            assertEquals(50, widget.rect.getLowerLeftX(), 0.01);
            assertEquals(70, widget.rect.getLowerLeftY(), 0.01);
        }
    }

    @Test
    void loteRegistraOMotivoSoDoArquivoCujaPosicaoNaoCabe() throws Exception {
        byte[] zip = mvc.perform(signBatch(1, 1).param("placements", "[{\"page\":4,\"x\":10,\"y\":10},null]"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Signed-Count", "1"))
            .andExpect(header().string("X-Failed-Count", "1"))
            .andReturn().getResponse().getContentAsByteArray();

        Map<String, byte[]> files = unzip(zip);
        assertEquals("Error: Invalid page number: 4. Document has 1 page.",
            new String(files.get("doc1.pdf.error.txt"), StandardCharsets.UTF_8));
        assertTrue(files.containsKey("doc2_signed.pdf"));
    }

    @Test
    void lotComPlacementsDeTamanhoErradoRecusaTudoCom400() throws Exception {
        mvc.perform(signBatch(1, 1).param("placements", "[null]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE_PLACEMENT"))
            .andExpect(jsonPath("$.error").value(
                "placements must be a JSON array with one item per document (2 expected)."));
    }

    @Test
    void loteComPlacementsQueNaoEhJsonRecusaCom400() throws Exception {
        mvc.perform(signBatch(1).param("placements", "{nao-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("placements is not valid JSON."));
    }
}
