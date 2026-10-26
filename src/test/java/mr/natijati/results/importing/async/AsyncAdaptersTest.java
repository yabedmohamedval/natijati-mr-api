package mr.natijati.results.importing.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import mr.natijati.results.exam.*;
import mr.natijati.results.importing.ImportStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncAdaptersTest {
    @TempDir Path directory;
    @Test void filesystemObjectIsDurableImmutableAndPathIsControlled() throws Exception {
        var storage=new FileSystemImportObjectStorage(directory.resolve("objects"));
        Path source=Files.writeString(directory.resolve("source"),"synthetic");
        String key="imports/"+UUID.randomUUID()+"/source.xlsx";
        storage.store(key,source);Files.delete(source);
        assertThat(storage.exists(key)).isTrue();
        storage.download(key,source);assertThat(Files.readString(source)).isEqualTo("synthetic");
        assertThatThrownBy(()->storage.store(key,source)).isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(()->storage.download("../../secret",source)).isInstanceOf(IllegalArgumentException.class);
        storage.delete(key);storage.delete(key);assertThat(storage.exists(key)).isFalse();
    }
    @Test void dispatcherSendsOnlyImportIdAndReturnsOperationWithoutPolling() throws Exception {
        var http=mock(GoogleImportHttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"name\":\"projects/test/locations/test/operations/123\"}");
        when(http.sendJson(any(),any())).thenReturn(response);
        var dispatcher=new CloudRunImportJobDispatcher(http,new ObjectMapper(),"projects/test/locations/europe-west1/jobs/importer");
        UUID id=UUID.randomUUID();assertThat(dispatcher.dispatch(id)).endsWith("operations/123");
        var body=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(http).sendJson(eq(URI.create("https://run.googleapis.com/v2/projects/test/locations/europe-west1/jobs/importer:run")),body.capture());
        assertThat(new ObjectMapper().readTree(body.getValue())).isEqualTo(new ObjectMapper().readTree(
            "{\"overrides\":{\"containerOverrides\":[{\"env\":[{\"name\":\"IMPORT_ID\",\"value\":\""+id+"\"}]}]}}"));
        verifyNoMoreInteractions(http);
    }
    @Test void dispatchErrorDoesNotExposeCloudResponseOrCredentials() throws Exception {
        var http=mock(GoogleImportHttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(403);when(response.body()).thenReturn("private upstream details");
        when(http.sendJson(any(),any())).thenReturn(response);
        var dispatcher=new CloudRunImportJobDispatcher(http,new ObjectMapper(),"projects/test/locations/europe-west1/jobs/importer");
        assertThatThrownBy(()->dispatcher.dispatch(UUID.randomUUID())).isInstanceOf(IOException.class)
            .hasMessage("Déclenchement Job non confirmé");
    }
    @Test void objectOutageNeverCreatesExecutionOrDispatchAndRemovesStaging() throws Exception {
        var staging=mock(ImportStorageService.class);var objects=mock(ImportObjectStorage.class);
        var executions=mock(ImportExecutionRepository.class);var dispatcher=mock(ImportJobDispatcher.class);
        var file=new MockMultipartFile("file","synthetic.xlsx","application/octet-stream",new byte[]{1});
        Path local=directory.resolve("temporary.xlsx");when(staging.store(file)).thenReturn(local);
        doThrow(new IOException("synthetic outage")).when(objects).store(anyString(),eq(local));
        var service=new ImportSubmissionService(staging,objects,executions,dispatcher);
        assertThatThrownBy(()->service.submit(file,ExamType.BAC,2026,SessionType.NORMAL)).isInstanceOf(IOException.class);
        verifyNoInteractions(executions,dispatcher);verify(staging).delete(local);verify(objects).delete(anyString());
    }
    @Test void gcsUsesPrivateObjectApiAndTreatsMissingDeleteAsSuccess() throws Exception {
        var http=mock(GoogleImportHttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<Void> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);
        when(http.<Void>send(any(),anyString(),any(),any())).thenReturn(response);
        var storage=new GcsImportObjectStorage(http,"private-imports-test");
        String key="imports/"+UUID.randomUUID()+"/source.xlsx";
        assertThat(storage.exists(key)).isFalse();storage.delete(key);
        var uri=org.mockito.ArgumentCaptor.forClass(URI.class);
        verify(http,times(2)).send(uri.capture(),anyString(),any(),any());
        assertThat(uri.getAllValues()).allSatisfy(value->{
            assertThat(value.getHost()).isEqualTo("storage.googleapis.com");
            assertThat(value.getRawPath()).contains("imports%2F").endsWith("%2Fsource.xlsx");
        });
    }
    @Test void gcsUploadIsCreateOnlyAndCloudErrorsAreMasked() throws Exception {
        var http=mock(GoogleImportHttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<Void> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(http.<Void>send(any(),anyString(),any(),any())).thenReturn(response);
        var storage=new GcsImportObjectStorage(http,"private-imports-test");
        String key="imports/"+UUID.randomUUID()+"/source.xlsx";
        Path source=Files.writeString(directory.resolve("source.xlsx"),"synthetic");
        assertThatThrownBy(()->storage.store(key,source)).hasMessage("Opération stockage impossible");
        var uri=org.mockito.ArgumentCaptor.forClass(URI.class);
        verify(http).send(uri.capture(),eq("POST"),any(),any());
        assertThat(uri.getValue().getRawQuery()).contains("ifGenerationMatch=0").contains("uploadType=media");
    }
    @Test void invalidJobConfigurationDoesNotResolveAdc() {
        var http=mock(GoogleImportHttpClient.class);
        var dispatcher=new CloudRunImportJobDispatcher(http,new ObjectMapper(),"");
        assertThatThrownBy(()->dispatcher.dispatch(UUID.randomUUID())).isInstanceOf(IOException.class);
        verifyNoInteractions(http);
    }
    @Test void progressUsesTwoDecimalsAndUnknownTotalIsNull() {
        var e=mock(ImportExecution.class);
        when(e.expectedRows()).thenReturn(121140L);when(e.writtenRows()).thenReturn(47500L);
        assertThat(ImportExecutionResponse.from(e).progressPercent()).isEqualByComparingTo("39.21");
        when(e.expectedRows()).thenReturn(null);
        assertThat(ImportExecutionResponse.from(e).progressPercent()).isNull();
    }
}
