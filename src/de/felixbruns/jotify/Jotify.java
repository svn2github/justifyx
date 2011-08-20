package de.felixbruns.jotify;

import java.awt.Image;
import java.util.List;
import java.util.concurrent.TimeoutException;

import de.felixbruns.jotify.exceptions.AuthenticationException;
import de.felixbruns.jotify.exceptions.ConnectionException;
import de.felixbruns.jotify.media.Album;
import de.felixbruns.jotify.media.Artist;
import de.felixbruns.jotify.media.Playlist;
import de.felixbruns.jotify.media.PlaylistContainer;
import de.felixbruns.jotify.media.Result;
import de.felixbruns.jotify.media.Track;
import de.felixbruns.jotify.media.User;

public interface Jotify extends Runnable {
	/**
	 * Login to Spotify using the specified username and password.
	 * 
	 * @param username Username to use.
	 * @param password Corresponding password.
	 * 
	 * @throws ConnectionException
	 * @throws AuthenticationException
	 */
	public void login(String username, String password) throws ConnectionException, AuthenticationException;
	
	/**
	 *  Closes the connection to a Spotify server.
	 *  
	 *  @throws ConnectionException
	 */
	public void close() throws ConnectionException;
	
	/**
	 * Get user info.
	 * 
	 * @return A {@link User} object.
	 * 
	 * @throws IllegalStateException
	 * 
	 * @see User
	 */
	public User user() throws TimeoutException;
	
	/**
	 * Fetch a toplist.
	 * 
	 * @param type     A toplist type. e.g. "artist", "album" or "track".
	 * @param region   A region code or null. e.g. "SE" or "DE".
	 * @param username A username or null.
	 * 
	 * @return A {@link Result} object.
	 * 
	 * @see Result
	 */
	public Result toplist(String type, String region, String username) throws TimeoutException;
	
	/**
	 * Search for an artist, album or track.
	 * 
	 * @param query Your search query.
	 * 
	 * @return A {@link Result} object.
	 * 
	 * @see Result
	 */
	public Result search(String query) throws TimeoutException;
	
	/**
	 * Get an image (e.g. artist portrait or cover) by requesting
	 * it from the server or loading it from the local cache, if
	 * available.
	 * 
	 * @param id Id of the image to get.
	 * 
	 * @return An {@link Image} or null if the request failed.
	 * 
	 * @see Image
	 */
	public Image image(String id) throws TimeoutException;
	
	/**
	 * Browse artist info.
	 * 
	 * @param artist An {@link Artist} object identifying the artist to browse.
	 * 
	 * @return A new {@link Artist} object holding more information about
	 *         the artist or null on failure.
	 * 
	 * @see Artist
	 */
	public Artist browse(Artist artist) throws TimeoutException;
	
	/**
	 * Browse album info.
	 * 
	 * @param album An {@link Album} object identifying the album to browse.
	 * 
	 * @return A new {@link Album} object holding more information about
	 *         the album or null on failure.
	 * 
	 * @see Album
	 */
	public Album browse(Album album) throws TimeoutException;
	
	/**
	 * Browse track info.
	 * 
	 * @param track A {@link Track} object identifying the track to browse.
	 * 
	 * @return A {@link Track} object or null on failure.
	 * 
	 * @see Track
	 */
	public Track browse(Track track) throws TimeoutException;
	
	/**
	 * Browse information for multiple tracks.
	 * 
	 * @param tracks A {@link List} of {@link Track} objects identifying
	 *               the tracks to browse.
	 * 
	 * @return A list of {@link Track} objects or null on failure.
	 * 
	 * @see Track
	 */
	public List<Track> browse(List<Track> tracks) throws TimeoutException;
	
	/**
	 * Browse artist info by id.
	 * 
	 * @param id An id identifying the artist to browse.
	 * 
	 * @return An {@link Artist} object holding more information about
	 *         the artist or null on failure.
	 * 
	 * @see Artist
	 */
	public Artist browseArtist(String id) throws TimeoutException;
	
	/**
	 * Browse album info by id.
	 * 
	 * @param id An id identifying the album to browse.
	 * 
	 * @return An {@link Album} object holding more information about
	 *         the album or null on failure.
	 * 
	 * @see Album
	 */
	public Album browseAlbum(String id) throws TimeoutException;
	
	/**
	 * Browse track info by id.
	 * 
	 * @param id An id identifying the track to browse.
	 * 
	 * @return A {@link Track} object or null on failure.
	 * 
	 * @see Track
	 */
	public Track browseTrack(String id) throws TimeoutException;
	
	/**
	 * Browse information for multiple tracks by id.
	 * 
	 * @param ids A {@link List} of ids identifying the tracks to browse.
	 * 
	 * @return A list of {@link Track} objects or null on failure.
	 * 
	 * @see Track
	 */
	public List<Track> browseTracks(List<String> ids) throws TimeoutException;
	
	/**
	 * Request a replacement track.
	 * 
	 * @param track The track to search the replacement for.
	 * 
	 * @return A {@link Track} object.
	 * 
	 * @see Track
	 */
	public Track replacement(Track track) throws TimeoutException;
	
	/**
	 * Request multiple replacement track.
	 * 
	 * @param tracks The tracks to search the replacements for.
	 * 
	 * @return A list of {@link Track} objects.
	 * 
	 * @see Track
	 */
	public List<Track> replacement(List<Track> tracks) throws TimeoutException;
	
	/**
	 * Get stored user playlists.
	 * 
	 * @return A {@link PlaylistContainer} holding {@link Playlist} objects
	 *         or an empty {@link PlaylistContainer} on failure.
	 *         Note: {@link Playlist} objects only hold id and author and need
	 *         to be loaded using {@link #playlist(String)}.
	 * 
	 * @see PlaylistContainer
	 */
	public PlaylistContainer playlistContainer() throws TimeoutException;
	
	/**
	 * Get a playlist.
	 * 
	 * @param id     Id of the playlist to load.
	 * @param cached Whether to use a cached version if available or not.
	 * 
	 * @return A {@link Playlist} object or null on failure.
	 * 
	 * @see Playlist
	 */
	public Playlist playlist(String id, boolean cached) throws TimeoutException;
	
	/**
	 * Get a playlist.
	 * 
	 * @param id Id of the playlist to load.
	 * 
	 * @return A {@link Playlist} object or null on failure.
	 * 
	 * @see Playlist
	 */
	public Playlist playlist(String id) throws TimeoutException;
	
}
