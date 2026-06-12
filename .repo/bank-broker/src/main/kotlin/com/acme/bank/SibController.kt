package com.acme.bank

import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Exposes the /sib/* endpoints tera-cloud-user's bankBrokerService /
// newBankBrokerService Feign clients call.
@RestController
@RequestMapping("/sib")
class SibController(
    private val sibService: SibService,
) {
    @GetMapping("/customers/{userNo}")
    fun customerQuery(@PathVariable userNo: String) = sibService.query(userNo)

    @PostMapping("/customers")
    fun customerRegister(req: Map<String, Any>) = sibService.register(req)

    @PutMapping("/customers")
    fun customerUpdate(req: Map<String, Any>) = sibService.update(req)

    @PostMapping("/inquiry/customer")
    fun inquiryCustomer(req: Map<String, Any>) = sibService.inquiry(req)

    @PostMapping("/fund/withdraw")
    fun fundWithdraw(req: Map<String, Any>) = sibService.withdraw(req)
}
