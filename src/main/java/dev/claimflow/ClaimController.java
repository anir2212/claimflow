package dev.claimflow;

import java.io.IOException;
import java.security.Principal;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/claims")
public class ClaimController {
    private final ClaimService service;
    private final ClaimSubmissionCoordinator submissions;
    private final PolicyService policy;
    private final ReceiptImageIntake images;

    public ClaimController(ClaimService service, ClaimSubmissionCoordinator submissions,
                           PolicyService policy, ReceiptImageIntake images) {
        this.service = service;
        this.submissions = submissions;
        this.policy = policy;
        this.images = images;
    }

    @PostMapping
    @PreAuthorize("hasRole('EMPLOYEE')")
    public Claim submit(@RequestHeader("Idempotency-Key") String key,
                        @Valid @RequestBody ClaimService.Submit input, Principal principal) {
        return submissions.submit(principal.getName(), key, input, false);
    }

    @PostMapping("/async")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public Claim submitAsync(@RequestHeader("Idempotency-Key") String key,
                             @Valid @RequestBody ClaimService.Submit input, Principal principal) {
        return submissions.submit(principal.getName(), key, input, true);
    }

    /** OCR seam: the image's text is queued through the same async rules as typed text. */
    @PostMapping(path = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('EMPLOYEE')")
    public Claim submitImage(@RequestHeader("Idempotency-Key") String key,
                             @RequestParam String category, @RequestParam long amountCents,
                             @RequestPart("receipt") MultipartFile receipt,
                             Principal principal) throws IOException {
        return images.submit(principal.getName(), key, category, amountCents, receipt.getBytes(),
            receipt.getContentType());
    }

    public record ReceiptCheck(String receiptText) {}

    @PostMapping("/{id}/receipt-check")
    @PreAuthorize("hasRole('MANAGER')")
    public Claim checkReceipt(@PathVariable String id, @RequestBody ReceiptCheck input,
                              Principal principal) {
        return service.resolveReceipt(id, principal.getName(), input.receiptText());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<Claim> mine(Principal principal) { return service.mine(principal.getName()); }

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE')")
    public List<Claim> queue() { return service.queue(); }

    @GetMapping("/receipt-check")
    @PreAuthorize("hasRole('MANAGER')")
    public List<Claim> receiptCheckQueue() { return service.needsHumanCheck(); }

    @GetMapping("/approved")
    @PreAuthorize("hasRole('FINANCE')")
    public List<Claim> approved() { return service.approved(); }

    @GetMapping("/stats")
    @PreAuthorize("hasRole('FINANCE')")
    public ClaimService.Stats stats() { return service.stats(); }

    @GetMapping("/{id}")
    public Claim get(@PathVariable String id, Authentication auth) {
        Claim claim = service.get(id);
        checkAccess(claim, auth);
        return claim;
    }

    @GetMapping("/{id}/audit")
    public List<AuditEvent> audit(@PathVariable String id, Authentication auth) {
        checkAccess(service.get(id), auth);
        return service.audit(id);
    }

    @GetMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE')")
    public PolicyService.Review review(@PathVariable String id) {
        return policy.review(service.get(id), null, false);
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('MANAGER')")
    public Claim decide(@PathVariable String id, @Valid @RequestBody ClaimService.Decision decision,
                        Principal principal) {
        return service.decide(id, principal.getName(), decision);
    }

    @PostMapping("/{id}/paid")
    @PreAuthorize("hasRole('FINANCE')")
    public Claim paid(@PathVariable String id, Principal principal) {
        return service.markPaid(id, principal.getName());
    }

    private void checkAccess(Claim claim, Authentication auth) {
        if (!claim.getEmployeeId().equals(auth.getName()) && auth.getAuthorities().stream().noneMatch(
            role -> role.getAuthority().equals("ROLE_MANAGER") || role.getAuthority().equals("ROLE_FINANCE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Claim belongs to another employee");
        }
    }
}
