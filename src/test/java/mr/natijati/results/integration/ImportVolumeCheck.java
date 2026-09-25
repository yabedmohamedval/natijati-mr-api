package mr.natijati.results.integration;

import mr.natijati.results.exam.*;
import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: mvn -B -Dit.test=ImportVolumeCheck -Dimport.volume.rows=10000 verify */
class ImportVolumeCheck extends ImportIntegrationSupport {
    @Test void representativeVolume() throws Exception {
        int rows=Integer.getInteger("import.volume.rows",10000);
        long generationStart=System.nanoTime();
        byte[] file=excel(ExamType.BAC,rows,false,false,true);
        double generationSeconds=(System.nanoTime()-generationStart)/1e9;
        System.gc();
        var pools=ManagementFactory.getMemoryPoolMXBeans();
        pools.forEach(p->p.resetPeakUsage());
        long start=System.nanoTime();
        var response=upload(ExamType.BAC,SessionType.NORMAL,file);
        double seconds=(System.nanoTime()-start)/1e9;
        long peak=pools.stream().filter(p->p.getType()==java.lang.management.MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum();
        System.out.printf("IMPORT_VOLUME generationSeconds=%.3f columns=18 rows=%d seconds=%.3f rowsPerSecond=%.1f peakHeapPoolsMiB=%.1f fileMiB=%.2f%n",generationSeconds,rows,seconds,rows/seconds,peak/1048576.0,file.length/1048576.0);
        assertThat(response.batchStatus()).isEqualTo("COMPLETED");assertThat(response.importedRows()).isEqualTo(rows);noTemporaryFiles();
    }
}
