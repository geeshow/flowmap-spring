package com.acme.bank

import org.springframework.stereotype.Service

@Service
class SibService {
    fun query(userNo: String): Map<String, Any> = mapOf("userNo" to userNo)
    fun register(req: Map<String, Any>): Map<String, Any> = req
    fun update(req: Map<String, Any>): Map<String, Any> = req
    fun inquiry(req: Map<String, Any>): Map<String, Any> = req
    fun withdraw(req: Map<String, Any>): Map<String, Any> = req
}
