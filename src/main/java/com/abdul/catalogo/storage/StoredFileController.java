package com.abdul.catalogo.storage;

import com.abdul.catalogo.storage.dto.FileIntentRequest;
import com.abdul.catalogo.storage.dto.FileIntentResponse;
import com.abdul.catalogo.storage.dto.StoredFileResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class StoredFileController {
    private final StoredFileService service;
    public StoredFileController(StoredFileService service) { this.service = service; }

    @PostMapping("/api/v1/files/intents")
    public FileIntentResponse intent(@Valid @RequestBody FileIntentRequest request) { return service.createIntent(request); }
    @PutMapping("/api/v1/files/intents/{id}/content")
    public StoredFileResponse upload(@PathVariable String id, @RequestParam("file") MultipartFile file) { return service.upload(id, file); }
    @PostMapping("/api/v1/files/intents/{id}/complete")
    public StoredFileResponse complete(@PathVariable String id) { return service.complete(id); }
    @GetMapping("/api/v1/files/{id}")
    public ResponseEntity<Resource> privateDownload(@PathVariable String id) { return response(service.download(id, false)); }
    @GetMapping("/public/files/{id}")
    public ResponseEntity<Resource> publicDownload(@PathVariable String id) { return response(service.download(id, true)); }

    private ResponseEntity<Resource> response(StoredFileService.Download download) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(download.fileName()).build().toString())
                .body(download.resource());
    }
}
