package rs.sud.eaukcija.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rs.sud.eaukcija.service.SyncService;

import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/listings")
    public ResponseEntity<Map<String, String>> syncListings() {
        if (syncService.isSyncing()) {
            return ResponseEntity.ok(Map.of("message", "Sync already in progress"));
        }
        new Thread(syncService::syncListings, "sync-listings").start();
        return ResponseEntity.ok(Map.of("message", "Listings sync started"));
    }

    @PostMapping("/details")
    public ResponseEntity<Map<String, String>> syncDetails() {
        if (syncService.isSyncing()) {
            return ResponseEntity.ok(Map.of("message", "Sync already in progress"));
        }
        new Thread(syncService::syncDetails, "sync-details").start();
        return ResponseEntity.ok(Map.of("message", "Details sync started"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "syncing", syncService.isSyncing(),
                "status", syncService.getSyncStatus(),
                "progress", syncService.getProgress(),
                "total", syncService.getTotalPages()
        ));
    }
}
