package mr.natijati.results.importing;

import mr.natijati.results.exam.ExamType;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** SAX -> bounded temporary row spool: no XSSF cell graph, no producer thread. */
final class XlsxRowSpool {
    private XlsxRowSpool() { }
    static Path create(Path source, ExamType exam, int maxRows) throws Exception {
        Path spool = Files.createTempFile(source.getParent(), "rows-", ".tmp");
        boolean complete = false;
        try (var pkg = OPCPackage.open(source.toFile(), PackageAccess.READ);
             var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(spool)))) {
            var reader = new XSSFReader(pkg);
            reader.setUseReadOnlySharedStringsTable(true);
            var strings = reader.getSharedStringsTable();
            var sheets = reader.getSheetsData();
            if (!sheets.hasNext()) throw new InvalidExamRowException(exam, 0, "WORKBOOK", "Feuille absente");
            try (var sheet = sheets.next()) {
                if (sheets.hasNext()) throw new InvalidExamRowException(exam, 0, "WORKBOOK", "Une seule feuille est requise");
                var parser = XMLHelper.newXMLReader();
                parser.setContentHandler(new DefaultHandler() {
                    int row, column, records;
                    String type;
                    boolean capture;
                    final StringBuilder value = new StringBuilder();
                    Map<Integer,String> cells;
                    @Override public void startElement(String uri,String local,String q,Attributes a) {
                        switch(local) {
                            case "row" -> { row=Integer.parseInt(a.getValue("r")); cells=new LinkedHashMap<>(); }
                            case "c" -> {
                                column=new CellReference(a.getValue("r")).getCol();
                                if(column<0 || column>=64) throw issue("Trop de colonnes");
                                type=a.getValue("t"); value.setLength(0);
                                if("e".equals(type))throw issue("Cellule en erreur interdite");
                            }
                            case "f" -> throw issue("Formule interdite");
                            case "v", "t" -> capture=true;
                        }
                    }
                    @Override public void characters(char[] chars,int start,int length) {
                        if(capture) {
                            if(value.length()+length>8192)throw issue("Cellule trop longue");
                            value.append(chars,start,length);
                        }
                    }
                    @Override public void endElement(String uri,String local,String q) throws org.xml.sax.SAXException {
                        if(local.equals("v")||local.equals("t"))capture=false;
                        if(local.equals("c")) {
                            String text=value.toString();
                            if("s".equals(type))text=strings.getItemAt(Integer.parseInt(text)).getString();
                            if(text.length()>8192)throw issue("Cellule trop longue");
                            cells.put(column,text.trim());
                        }
                        if(local.equals("row") && cells.values().stream().anyMatch(v->!v.isBlank())) {
                            if(++records>maxRows+1)throw issue("Limite de lignes dépassée : "+maxRows);
                            try {
                                output.writeInt(row);output.writeInt(cells.size());
                                for(var entry:cells.entrySet()){output.writeInt(entry.getKey());output.writeUTF(entry.getValue());}
                            }catch(IOException ex){throw new org.xml.sax.SAXException(ex);}
                        }
                    }
                    private InvalidExamRowException issue(String reason) {
                        return new InvalidExamRowException(exam,row,"CELL_"+(column+1),reason);
                    }
                });
                parser.parse(new InputSource(sheet));
            }
            complete=true;
            return spool;
        } finally { if(!complete)Files.deleteIfExists(spool); }
    }
}
