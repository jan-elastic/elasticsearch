package org.elasticsearch.plugin.reindex;

import org.elasticsearch.action.bulk.BulkItemResponse.Failure;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import static org.elasticsearch.plugin.reindex.BulkIndexByScrollResponse.Fields.CREATED;

public class ReindexResponse extends BulkIndexByScrollResponse {
    private long created;

    public ReindexResponse() {
    }

    public ReindexResponse(long took, long created, long updated, int batches, long versionConflicts, long noops, List<Failure> failures) {
        super(took, updated, batches, versionConflicts, noops, failures);
        this.created = created;
    }

    public long created() {
        return created;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        // NOCOMMIT need a round trip test for this
        super.writeTo(out);
        out.writeVLong(created);
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        created = in.readVLong();
    }

    static final class Fields {
        static final XContentBuilderString CREATED = new XContentBuilderString("created");
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        super.toXContent(builder, params);
        builder.field(CREATED, created);
        return builder;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("IndexBySearchResponse[");
        builder.append("took=").append(took());
        builder.append(",created=").append(created);
        builder.append(",updated=").append(updated());
        builder.append(",batches=").append(batches());
        builder.append(",versionConflicts=").append(versionConflicts());
        truncatedFailures(builder);
        return builder.append("]").toString();
    }

    /**
     * Get the first few failures to build a useful for toString.
     */
    protected void truncatedFailures(StringBuilder builder) {
        builder.append(",failures=[");
        Iterator<Failure> failures = failures().iterator();
        int written = 0;
        while (failures.hasNext() && written < 3) {
            Failure failure = failures.next();
            builder.append(failure.getMessage());
            if (written != 0) {
                builder.append(", ");
            }
            written++;
        }
        builder.append(']');
    }
}
