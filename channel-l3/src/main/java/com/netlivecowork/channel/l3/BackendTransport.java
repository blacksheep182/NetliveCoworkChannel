package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.*;
import java.io.IOException;
import java.util.Map;

/** L3-only transport seam. Backend protocol bodies and L2 packets stay unchanged. */
interface BackendTransport {
    PythonBackendClient.Reply exchange(BackendService service,String method,String path,String body,Map<String,String> headers)
        throws IOException,InterruptedException;
    default PythonBackendClient.Reply get(BackendService s,String p)throws IOException,InterruptedException{return exchange(s,"GET",p,null,Map.of());}
    default PythonBackendClient.Reply post(BackendService s,String p,String b)throws IOException,InterruptedException{return exchange(s,"POST",p,b,Map.of());}
    default PythonBackendClient.Reply delete(BackendService s,String p)throws IOException,InterruptedException{return exchange(s,"DELETE",p,null,Map.of());}
    FileReadResult readFile(BackendService service,String id,FileMetadata metadata)throws IOException,InterruptedException;
}
