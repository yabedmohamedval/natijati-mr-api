package mr.natijati.results.importing.async;

import mr.natijati.results.exam.*;
import mr.natijati.results.importing.ImportStorageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class ImportSubmissionService {
    private final ImportStorageService staging;
    private final ImportObjectStorage storage;
    private final ImportExecutionRepository executions;
    private final ImportJobDispatcher dispatcher;
    public ImportSubmissionService(ImportStorageService staging, ImportObjectStorage storage,
        ImportExecutionRepository executions, ImportJobDispatcher dispatcher) {
        this.staging=staging; this.storage=storage; this.executions=executions; this.dispatcher=dispatcher;
    }
    public ImportExecutionResponse submit(MultipartFile file, ExamType exam, int year, SessionType type) throws IOException {
        if (year < 1900 || year > 2100) throw new IllegalArgumentException("Année hors plage 1900–2100");
        Path local=staging.store(file); // security envelope only; full preflight belongs to worker
        UUID id=UUID.randomUUID();
        String key="imports/"+id+"/source"+(local.toString().endsWith(".xlsx") ? ".xlsx" : ".xls");
        try {
            try {
                storage.store(key,local);
                executions.create(id,exam,year,type,key);
            } catch (IOException | RuntimeException failure) {
                try { storage.delete(key); } catch (Exception cleanup) {
                    LoggerFactory.getLogger(getClass()).warn("import_orphan_cleanup_pending importId={}",id);
                }
                throw failure;
            }
            try { executions.dispatched(id,dispatcher.dispatch(id)); }
            catch (IOException | RuntimeException failure) {
                // Network timeouts cannot prove the job was NOT started. Retain source and reservation.
                executions.dispatchUnconfirmed(id);
                LoggerFactory.getLogger(getClass()).warn("import_dispatch_unconfirmed importId={}",id);
            }
            return ImportExecutionResponse.from(executions.get(id));
        } finally { staging.delete(local); }
    }
}
