package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.*;
import java.io.IOException;
import java.util.Map;

final class RoutedBackendTransport implements BackendTransport {
    private final BackendTransport http;
    private final ConnectorHub connectors;
    RoutedBackendTransport(BackendTransport http,ConnectorHub connectors){this.http=http;this.connectors=connectors;}
    public PythonBackendClient.Reply exchange(BackendService service,String method,String path,String body,Map<String,String> headers)
            throws IOException,InterruptedException {
        return (service.accessType()==AccessType.CONNECTOR?connectors:http).exchange(service,method,path,body,headers);
    }
    public FileReadResult readFile(BackendService service,String id,FileMetadata metadata)throws IOException,InterruptedException{
        return (service.accessType()==AccessType.CONNECTOR?connectors:http).readFile(service,id,metadata);
    }
}
