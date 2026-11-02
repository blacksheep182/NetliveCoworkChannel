package com.netlivecowork.channel.contract;
import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.json.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationContractTest {
    @Test void sharedNavigationOperationsRoundTripWithoutPlatformFields() {
        var operations=List.<ChannelOperation>of(new ChannelOperation.WorkspaceList(),new ChannelOperation.SelectionGet(),
            new ChannelOperation.WorkspaceSelect("sws-project",2),new ChannelOperation.SessionSelect("target",3),
            new ChannelOperation.SessionList(null,new PageRequest(null,10),"sws-project"),
            new ChannelOperation.InputSubmit("target",ChannelOperation.Interpretation.LITERAL,List.of(new ContentPart.Text("p","/use is text")),null,4L));
        for(var operation:operations){
            var encoded=PacketJson.enc(operation);
            assertEquals(operation,PacketJson.decOperation(operation.type(),encoded.get("payload")));
            assertFalse(encoded.toString().contains("viewId"));assertFalse(encoded.toString().contains("web"));
        }
    }
    @Test void sharedSelectionAndWorkspaceResultsRoundTrip() {
        var workspace=new SessionWorkspace("sws-project","project");var selection=new NavigationSelection("scope","sws-project",null,3);
        var results=List.<OperationResult>of(new OperationResult.WorkspaceList(List.of(workspace),selection),new OperationResult.WorkspaceSelect(selection),new OperationResult.SelectionGet(selection),
            new OperationResult.SessionSelect("scope","target",4,"sws-project"),
            new OperationResult.SessionGet(new SessionSummary("target","Session",null,true,null,workspace)));
        for(var result:results)assertEquals(result,PacketJson.decOperationResult(PacketJson.enc(result)));
    }
}
