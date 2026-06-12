package com.acme.twice

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// Exposes the endpoints tera-cloud-user's `twiceClient` Feign calls.
@RestController
@RequestMapping("/internal/user")
class TwiceUserController(
    private val twiceUserService: TwiceUserService,
) {
    @PostMapping("/for-login")
    fun getForLogin(input: Map<String, String>): UserView =
        twiceUserService.login(input["userId"] ?: "")

    @GetMapping("/for-refresh")
    fun getForTokenRefresh(@RequestParam userId: String): UserView =
        twiceUserService.refresh(userId)
}

data class UserView(val id: String, val token: String)
