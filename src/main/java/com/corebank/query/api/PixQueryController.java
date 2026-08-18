package com.corebank.query.api;

import com.corebank.query.dto.BalanceDTO;
import com.corebank.query.dto.PixStatementReadDTO;
import com.corebank.query.service.PixQueryService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/pix")
public class PixQueryController {
    private final PixQueryService pixQueryService;

    public PixQueryController(PixQueryService pixQueryService) {
        this.pixQueryService = pixQueryService;
    }

    @GetMapping("/{accountId}/balance")
    public BalanceDTO getBalance(@PathVariable Long accountId) {
        return pixQueryService.getBalance(accountId);
    }

    @GetMapping("/{accountId}/statement")
    public List<PixStatementReadDTO> getStatement(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return pixQueryService.getPixStatement(accountId, limit, offset);
    }
}
