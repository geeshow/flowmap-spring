package com.acme.funding

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Exposes the endpoint tera-cloud-user's `teraFundingClient` Feign calls.
@RestController
@RequestMapping("/internal/investment")
class InvestmentController(
    private val investmentService: InvestmentService,
) {
    @GetMapping("/current-summary")
    fun currentValidSummary(): SummaryView = investmentService.currentSummary()
}

data class SummaryView(val totalAmount: Long, val count: Int)
