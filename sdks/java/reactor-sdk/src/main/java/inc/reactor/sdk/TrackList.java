package inc.reactor.sdk;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The tracks a session declared, in the order it declared them.
 *
 * <p>Order is part of the contract. Collecting these into a name-keyed map would sort them
 * alphabetically and silently renumber what {@code tracks().get(0)} means for every caller, so they
 * stay a sequence and a lookup by name is a scan — a session declares a handful of tracks, not a
 * dictionary's worth.
 *
 * <p>Filters chain in either order:
 *
 * <pre>{@code
 * Track camera = reactor.tracks()
 *     .withKind(TrackKind.VIDEO)
 *     .withDirection(TrackDirection.RECVONLY)
 *     .one();
 * }</pre>
 */
public final class TrackList implements Iterable<Track> {

    private final List<Track> tracks;

    TrackList(List<Track> tracks) {
        this.tracks = List.copyOf(tracks);
    }

    /** @return how many tracks the session declared */
    public int size() {
        return tracks.size();
    }

    /** @return whether the session declared none */
    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    /**
     * @param index the position, in declaration order
     * @return the track at that position
     */
    public Track get(int index) {
        return tracks.get(index);
    }

    /** @return the tracks, in declaration order */
    public List<Track> asList() {
        return tracks;
    }

    /** @return a stream over the tracks, in declaration order */
    public Stream<Track> stream() {
        return tracks.stream();
    }

    @Override
    public Iterator<Track> iterator() {
        return tracks.iterator();
    }

    /**
     * @param name the declared name
     * @return the track with that name, or empty when the session declared none
     */
    public Optional<Track> byName(String name) {
        return tracks.stream().filter(track -> track.name().equals(name)).findFirst();
    }

    /**
     * @param kind video or audio
     * @return the tracks of that kind, in declaration order
     */
    public TrackList withKind(TrackKind kind) {
        return new TrackList(
                tracks.stream().filter(track -> track.kind() == kind).toList());
    }

    /**
     * @param direction which way media flows
     * @return the tracks in that direction, in declaration order
     */
    public TrackList withDirection(TrackDirection direction) {
        return new TrackList(
                tracks.stream().filter(track -> track.direction() == direction).toList());
    }

    /**
     * The single track this list holds.
     *
     * @return that track
     * @throws ReactorException when the list holds none, or more than one — a filter that matched
     *     three tracks and silently answered with the first is how an application ends up reading
     *     the wrong camera
     */
    public Track one() {
        if (tracks.size() == 1) {
            return tracks.get(0);
        }
        String found = tracks.isEmpty()
                ? "no tracks match"
                : "these match: " + tracks.stream().map(Track::name).toList();
        throw ReactorException.of(
                ErrorCode.INVALID_STATE.code(),
                "one() needs exactly one track, but " + found + ".",
                null,
                "tracks",
                null);
    }

    @Override
    public String toString() {
        return tracks.toString();
    }
}
