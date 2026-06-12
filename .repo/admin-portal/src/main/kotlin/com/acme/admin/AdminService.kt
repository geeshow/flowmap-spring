package com.acme.admin

import org.springframework.stereotype.Service

@Service
class AdminService(
    private val teraUserClient: TeraUserClient,   // S2S HTTP -> tera-cloud-user
) {
    fun loadUser(userNo: Long): Map<String, Any?> {
        val user = teraUserClient.getUser(userNo)
        val all = teraUserClient.findUsers(listOf(userNo))
        return mapOf("user" to user, "siblings" to all.size)
    }
}
