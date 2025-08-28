package com.canfeng.coze.plugin.controller;

import com.canfeng.coze.plugin.service.PdfService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/convert")
public class ConvertController {

    private final PdfService pdfService;

    public ConvertController(PdfService pdfService) {
        this.pdfService = pdfService;
    }

    @PostMapping("/pdf")
    public ResponseEntity<Map<String, Object>> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam("target_format") String target,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "author", required = false) String author
    ) throws Exception {
        return ResponseEntity.ok(pdfService.convert(file.getBytes(), target, title, author));
    }

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("ok", true);
    }
}
