package com.acme.admin

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping

// Calls tera-cloud-user's internal user endpoints. When tera-cloud-user has been
// analyzed into the same registry, these resolve to it (S2S), not external.
@FeignClient(name = "tera-cloud-user", url = "\${service-url.tera-user}")
interface TeraUserClient {
    @GetMapping("/internal/user/{userNo}")
    fun getUser(@PathVariable userNo: Long): Map<String, Any?>

    @PostMapping("/internal/user/users")
    fun findUsers(userNos: List<Long>): List<Map<String, Any?>>
}
