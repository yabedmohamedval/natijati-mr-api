package mr.natijati.results.integration;

import mr.natijati.results.exam.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Opt-in exact 40,000/65,000 failure scenario, excluded from default Failsafe patterns. */
class ImportAtomicityCheck extends ImportIntegrationSupport {
    @Test void failureAfterFortyThousandCommittedRowsLeavesNoResults() throws Exception {
        byte[] file=excel(ExamType.BAC,65000,false,false);
        jdbc.execute("CREATE FUNCTION stop_after_40000() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.candidate_number=50001 THEN RAISE EXCEPTION 'synthetic interruption after 40000'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER stop_after_40000 BEFORE INSERT ON bac_result FOR EACH ROW EXECUTE FUNCTION stop_after_40000()");
        long start=System.nanoTime();
        var response=upload(ExamType.BAC,SessionType.NORMAL,file);
        assertThat(response.batchStatus()).isEqualTo("FAILED");
        assertThat(response.sessionStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT sum(write_count) FROM batch_step_execution WHERE job_execution_id=?",Long.class,response.jobExecutionId())).isEqualTo(40000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bac_result",Long.class)).isZero();
        assertThat(response.importedRows()).isZero();
        assertThatThrownBy(()->sessions.publish(response.sessionId())).isInstanceOf(IllegalStateException.class);
        noTemporaryFiles();
        jdbc.execute("DROP TRIGGER stop_after_40000 ON bac_result");jdbc.execute("DROP FUNCTION stop_after_40000()");
        var retry=upload(ExamType.BAC,SessionType.NORMAL,excel(ExamType.BAC,2,false,false));
        assertThat(retry.sessionId()).isEqualTo(response.sessionId());assertThat(retry.importedRows()).isEqualTo(2);
        System.out.printf("IMPORT_ATOMICITY sourceRows=65000 committedBeforeFailure=40000 retainedAfterFailure=0 retryRows=2 seconds=%.3f%n",(System.nanoTime()-start)/1e9);
    }
}
