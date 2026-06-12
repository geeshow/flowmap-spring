package com.acme.twice

import org.springframework.stereotype.Service

@Service
class TwiceUserService {
    fun login(userId: String): UserView = UserView(userId, "token-$userId")
    fun refresh(userId: String): UserView = UserView(userId, "refreshed-$userId")
}
