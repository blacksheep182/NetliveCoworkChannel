package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.ResolvedDestination;
import java.util.concurrent.CompletionStage;

/** L2 → L1 目的地解析：只解析本 entryRef 的目的地，并按预期版本校验（INV-13）。 */
public interface DestinationResolver {

    CompletionStage<ResolvedDestination> resolve(String destinationRef, long expectedRevision);
}
