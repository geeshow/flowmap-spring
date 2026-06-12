package com.acme.admin

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/portal")
class AdminController(
    private val adminService: AdminService,
) {
    @GetMapping("/users/{userNo}")
    fun viewUser(@PathVariable userNo: Long) = adminService.loadUser(userNo)
}
