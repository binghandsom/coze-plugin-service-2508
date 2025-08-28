package com.canfeng.coze.plugin.service;

import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Author;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.epub.EpubWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

@org.springframework.stereotype.Service
public class PdfService {

  public Map<String, Object> convert(byte[] pdf, String target, String title, String author) throws Exception {
    target = (target == null ? "" : target).toLowerCase(Locale.ROOT).trim();
    if (!List.of("epub", "mobi", "azw3", "txt").contains(target)) {
      throw new IllegalArgumentException("target_format must be epub|mobi|azw3|txt");
    }

    boolean hasCalibre = which("ebook-convert") != null;
    String note = "";

    // 路线 A：Calibre 优先（四格式全通）
    if (hasCalibre) {
      try {
        byte[] out = runCalibre(pdf, target, title, author);
        return resp(filename(title, target), mime(target), b64(out), note);
      } catch (Exception e) {
        note = "ebook-convert 失败，已回退纯 Java：" + safeMsg(e);
      }
    }

    // 路线 B：纯 Java（支持 EPUB/TXT；MOBI/AZW3 → EPUB）
    String realTarget = target;
    if (target.equals("mobi") || target.equals("azw3")) {
      realTarget = "epub";
      note = (note + " ").trim() + "当前环境缺少 Calibre，已降级为 EPUB。";
    }

    String text = extractText(pdf);
    byte[] out = realTarget.equals("txt") ? text.getBytes(UTF_8) : buildEpub(text, title, author);
    return resp(filename(title, realTarget), mime(realTarget), b64(out), note.trim());
  }

  // ---------- Calibre ----------
  private static byte[] runCalibre(byte[] pdf, String target, String title, String author) throws Exception {
    Path tmp = Files.createTempDirectory("pdf2ebook_");
    try {
      Path in = tmp.resolve("in.pdf");
      Files.write(in, pdf);
      Path out = tmp.resolve("out." + target);

      List<String> cmd = new ArrayList<>(List.of("ebook-convert", in.toString(), out.toString()));
      if (title != null && !title.isBlank()) { cmd.add("--title"); cmd.add(title); }
      if (author != null && !author.isBlank()) { cmd.add("--authors"); cmd.add(author); }

      // 可选优化：cmd.add("--enable-heuristics");

      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      String log = new String(p.getInputStream().readAllBytes(), UTF_8);
      if (p.waitFor() != 0 || !Files.exists(out)) {
        throw new RuntimeException("Calibre error: " + log.substring(0, Math.min(800, log.length())));
      }
      return Files.readAllBytes(out);
    } finally {
      try { deleteRecursively(tmp); } catch (IOException ignored) {}
    }
  }

  private static String which(String cmd) {
    String path = System.getenv("PATH");
    if (path == null) return null;
    for (String p : path.split(File.pathSeparator)) {
      Path f = Paths.get(p).resolve(cmd);
      if (Files.isExecutable(f)) return f.toString();
    }
    return null;
  }

  // ---------- 纯 Java：PDF→文本，文本→EPUB ----------
  private static String extractText(byte[] pdf) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf)) {
      PDFTextStripper stripper = new PDFTextStripper();
      return stripper.getText(doc).trim();
    }
  }

  private static byte[] buildEpub(String text, String title, String author) throws IOException {
    Book book = new Book();
    book.getMetadata().addTitle(optional(title, "Untitled"));
    if (author != null && !author.isBlank()) {
      book.getMetadata().addAuthor(new Author(author));
    }

    // 极简切章：按空行拆分
    String[] chapters = text.split("\\n{2,}");
    if (chapters.length == 0) chapters = new String[]{""};

    for (int i = 0; i < chapters.length; i++) {
      String c = chapters[i];
      String html = "<html><body><h2>Chapter " + (i + 1) + "</h2><p>" +
          escape(c).replace("\n\n", "</p><p>").replace("\n", "<br/>") +
          "</p></body></html>";
      Resource res = new Resource(html.getBytes(StandardCharsets.UTF_8), "ch_" + (i + 1) + ".xhtml");
      book.addSection("Chapter " + (i + 1), res);
    }

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    new EpubWriter().write(book, bos);
    return bos.toByteArray();
  }

  // ---------- utils ----------
  private static Map<String, Object> resp(String fn, String mime, String b64, String note) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("filename", fn);
    m.put("mime_type", mime);
    m.put("content_base64", b64);
    if (!note.isBlank()) m.put("note", note);
    return m;
  }

  private static String filename(String title, String ext) {
    String base = optional(title, "book").trim().replaceAll("\\s+", "_");
    return base + "." + ext;
  }

  private static String mime(String f) {
    return Map.of(
        "epub", "application/epub+zip",
        "mobi", "application/x-mobipocket-ebook",
        "azw3", "application/vnd.amazon.ebook",
        "txt", "text/plain; charset=utf-8"
    ).getOrDefault(f, "application/octet-stream");
  }

  private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
  private static String escape(String s){ return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
  private static String optional(String v, String def){ return (v == null || v.isBlank()) ? def : v; }
  private static String safeMsg(Exception e){ String s = String.valueOf(e.getMessage()); return s.length() > 160 ? s.substring(0,160) : s; }

  private static void deleteRecursively(Path p) throws IOException {
    if (!Files.exists(p)) return;
    try (var walk = Files.walk(p)) {
      walk.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x);} catch(Exception ignored){} });
    }
  }
}
