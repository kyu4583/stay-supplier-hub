package stay.supplierhub.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import stay.supplierhub.search.SupplierContracts.ChunkFailure;
import stay.supplierhub.search.SupplierContracts.RoomOffer;
import stay.supplierhub.search.SupplierContracts.SupplierId;
import stay.supplierhub.search.SupplierContracts.SupplierSearchResult;
import stay.supplierhub.search.SupplierContracts.UnmappedRoomType;

public final class ChunkedCalls {

    static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    private static final String EMPTY_RESULT = "EMPTY_RESULT";

    private ChunkedCalls() {}

    public static Mono<SupplierSearchResult> collect(
            SupplierId supplier,
            List<String> codes,
            int chunkSize,
            int concurrency,
            Duration budget,
            Function<List<String>, Mono<SupplierSearchResult>> callPerChunk) {
        return Mono.defer(() -> {
            if (chunkSize < 1 || concurrency < 1) {
                return Mono.error(new IllegalArgumentException(
                        "chunkSize and concurrency must be positive: " + chunkSize + ", " + concurrency));
            }
            List<List<String>> chunks = split(codes == null ? List.of() : codes, chunkSize);
            if (chunks.isEmpty()) {
                return Mono.just(new SupplierSearchResult(supplier, List.of(), List.of(), List.of()));
            }
            Flux<ChunkResult> calls = Flux.range(0, chunks.size())
                    .flatMap(index -> call(supplier, index, chunks.get(index), callPerChunk), concurrency);
            if (budget != null) {
                calls = calls.take(budget);
            }
            return calls.collectList().map(done -> merge(supplier, chunks.size(), done));
        });
    }

    private static List<List<String>> split(List<String> codes, int chunkSize) {
        List<List<String>> chunks = new ArrayList<>();
        for (int from = 0; from < codes.size(); from += chunkSize) {
            chunks.add(List.copyOf(codes.subList(from, Math.min(from + chunkSize, codes.size()))));
        }
        return chunks;
    }

    private static Mono<ChunkResult> call(
            SupplierId supplier,
            int index,
            List<String> chunk,
            Function<List<String>, Mono<SupplierSearchResult>> callPerChunk) {
        return Mono.defer(() -> callPerChunk.apply(chunk))
                .map(result -> new ChunkResult(index, result))
                .defaultIfEmpty(new ChunkResult(index, failed(supplier, EMPTY_RESULT)))
                .onErrorResume(ex -> Mono.just(new ChunkResult(index, failed(supplier, ex.getClass().getSimpleName()))));
    }

    private static SupplierSearchResult merge(SupplierId supplier, int chunkCount, List<ChunkResult> done) {
        List<ChunkResult> ordered = new ArrayList<>(done);
        ordered.sort(Comparator.comparingInt(ChunkResult::index));
        List<RoomOffer> offers = new ArrayList<>();
        List<ChunkFailure> failures = new ArrayList<>();
        List<UnmappedRoomType> unmapped = new ArrayList<>();
        Set<Integer> finished = new LinkedHashSet<>();
        for (ChunkResult chunk : ordered) {
            finished.add(chunk.index());
            offers.addAll(chunk.result().offers());
            failures.addAll(chunk.result().failures());
            unmapped.addAll(chunk.result().unmappedRoomTypes());
        }
        for (int index = 0; index < chunkCount; index++) {
            if (!finished.contains(index)) {
                failures.add(new ChunkFailure(supplier, BUDGET_EXCEEDED));
            }
        }
        return new SupplierSearchResult(
                supplier, List.copyOf(offers), List.copyOf(failures), List.copyOf(unmapped));
    }

    private static SupplierSearchResult failed(SupplierId supplier, String reason) {
        return new SupplierSearchResult(supplier, List.of(), List.of(new ChunkFailure(supplier, reason)), List.of());
    }

    private record ChunkResult(int index, SupplierSearchResult result) {}
}
