package se.kth.castor.jdbl.deptree;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

public abstract class AbstractLineBasedParser extends AbstractParser
{
    public static final String ARTIFACT = "artifact = ";
    protected int lineIndex = 0;

    protected List<String> lines;

    protected List<String> splitLines(final Reader reader) throws IOException
    {
        String line = null;
        final BufferedReader br;
        if (reader instanceof BufferedReader) {
            br = (BufferedReader) reader;
        } else {
            br = new BufferedReader(reader);
        }
        lines = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            lines.add(line);
        }
        return lines;
    }

    protected String extractActiveProjectArtifact()
    {
        String artifact = null;
        //start at next line and consume all lines containing "artifact =" or "project: "; record the last line containing "artifact =".
        boolean artifactFound = false;
        while (this.lineIndex < this.lines.size() - 1) {
            String tempLine = this.lines.get(this.lineIndex + 1);
            boolean artifactLine = !artifactFound && tempLine.contains(ARTIFACT);
            boolean projectLine = artifactFound && tempLine.contains("project: ");
            if (artifactLine || projectLine) {
                if (tempLine.contains(ARTIFACT) && !tempLine.contains("active project artifact:")) {
                    artifact = StringUtils.substringBefore(StringUtils.substringAfter(tempLine, ARTIFACT), ";");
                    artifactFound = true;
                }
                this.lineIndex++;
            } else {
                break;
            }
        }
        return artifact;
    }
}
