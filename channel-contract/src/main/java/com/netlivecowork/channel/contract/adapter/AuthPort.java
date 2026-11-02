package com.netlivecowork.channel.contract.adapter;

import java.util.concurrent.CompletionStage;

/** 交互式账号授权端口。只经管理授权开放；authRef 绑定发起管理者、接入与有效期。 */
public interface AuthPort {

    CompletionStage<AuthSession> beginAuth();

    CompletionStage<AuthSession> authStatus(String authRef);

    CompletionStage<Void> cancelAuth(String authRef);
}
