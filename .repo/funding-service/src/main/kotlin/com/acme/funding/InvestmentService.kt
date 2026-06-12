package com.acme.funding

import org.springframework.stereotype.Service

@Service
class InvestmentService {
    fun currentSummary(): SummaryView = SummaryView(0, 0)
}
