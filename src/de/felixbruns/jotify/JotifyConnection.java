package de.felixbruns.jotify;

import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;

import javax.imageio.ImageIO;

import de.felixbruns.jotify.crypto.*;
import de.felixbruns.jotify.exceptions.*;
import de.felixbruns.jotify.media.*;
import de.felixbruns.jotify.media.Link.InvalidSpotifyURIException;
import de.felixbruns.jotify.media.parser.*;
import de.felixbruns.jotify.protocol.*;
import de.felixbruns.jotify.protocol.channel.*;
import de.felixbruns.jotify.util.*;

public class JotifyConnection implements Jotify, CommandListener {
	/*
	 * Values for browsing media.
	 */
	private static final int BROWSE_ARTIST = 1;
	private static final int BROWSE_ALBUM  = 2;
	private static final int BROWSE_TRACK  = 3;
	
	/*
	 * Session and protocol associated with this connection.
	 */
	protected Session  session;
	protected Protocol protocol;
	
	/*
	 * User information.
	 */
	private User      user;
	private Semaphore userSemaphore;
	
	/*
	 * Status and timeout.
	 */
	private boolean  running;
	private long     timeout;
	private TimeUnit unit;
	
	/**
	 * Create a new Jotify instance using the default {@link Cache}
	 * implementation and timeout value (10 seconds).
	 */
	public JotifyConnection(long timeout, TimeUnit unit){
		this.session = new Session();
		this.protocol = null;
		this.running = false;
		this.user = null;
		this.userSemaphore = new Semaphore(2);
		this.timeout = timeout;
		this.unit = unit;

		/* Acquire permits (country, prodinfo). */
		this.userSemaphore.acquireUninterruptibly(2);
	}

	/**
	 * Set timeout for requests.
	 * 
	 * @param timeout Timeout value to use.
	 * @param unit    TimeUnit to use for timeout.
	 */
	public void setTimeout(long timeout, TimeUnit unit){
		this.timeout = timeout;
		this.unit    = unit;
	}
	
	/**
	 * Set timeout for requests.
	 * 
	 * @param seconds Timeout in seconds to use.
	 */
	public void setTimeout(long seconds){
		this.timeout = seconds;
		this.unit    = TimeUnit.SECONDS;
	}
	
	/**
	 * Login to Spotify using the specified username and password.
	 * 
	 * @param username Username to use.
	 * @param password Corresponding password.
	 * 
	 * @throws ConnectionException
	 * @throws AuthenticationException
	 */
	public void login(String username, String password) throws ConnectionException, AuthenticationException {
		/* Check if we're already logged in. */
		if(this.protocol != null){
			throw new IllegalStateException("Already logged in!");
		}
		
		/* Authenticate session and get protocol. */
		this.protocol = this.session.authenticate(username, password);
		
		/* Create user object. */
		this.user = new User(username);
		
		/* Add command handler. */
		this.protocol.addListener(this);
		
		/* Start I/O thread. */
		new Thread(this, "I/O-Thread").start();
	}
	
	/**
	 * Closes the connection to a Spotify server.
	 * 
	 * @throws ConnectionException
	 */
	public void close() throws ConnectionException {
		/* This will make receivePacket return immediately. */
		if(this.protocol != null){
			this.protocol.disconnect();
		}
		
		/* Reset protocol to 'null'. */
		this.protocol = null;
	}
	
	/**
	 * Continuously receives packets in order to handle them.
	 */
	public void run(){
		/* Fail quietly. */
		if(this.running){
			return;
		}
		
		/* Check if we're logged in. */
		if(this.protocol == null){
			throw new IllegalStateException("You need to login first!");
		}
		
		this.running = true;
		
		/* Continuously receive packets until connection is closed. */
		try{
			while(true){
				if(this.protocol == null){
					break;
				}
				
				this.protocol.receivePacket();
			}
		}
		catch(ProtocolException e){
			/* Connection was closed. */
		}
		finally{
			this.running = false;
		}
	}
	
	/**
	 * Get user info.
	 * 
	 * @return A {@link User} object.
	 * 
	 * @see User
	 */
	public User user() throws TimeoutException {
		/* Wait for data to become available (country, prodinfo). */
		try{
			if(!this.userSemaphore.tryAcquire(2, this.timeout, this.unit)){
				throw new TimeoutException("Timeout while waiting for user data.");
			}
			
			return this.user;
		}
		catch(InterruptedException e){
			return null;
		}
		finally{
			/* Release so this can be called again. */
			this.userSemaphore.release(2);
		}
	}
	
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
	public Result toplist(String type, String region, String username) throws TimeoutException {
		/* Create channel callback and parameter map. */
		ChannelCallback callback   = new ChannelCallback();
		Map<String, String> params = new HashMap<String, String>();
		
		/* Add parameters. */
		params.put("type", type);
		params.put("region", region);
		params.put("username", username);
		
		/* Send toplist request. */
		try{
			this.protocol.sendToplistRequest(callback, params);
		}
		catch(ProtocolException e){
			return null;
		}
		
		/* Get data. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Create result from XML. */
		return XMLMediaParser.parseResult(data, "UTF-8");
	}
	
	/**
	 * Search for an artist, album or track.
	 * 
	 * @param query Your search query.
	 * 
	 * @return A {@link Result} object.
	 * 
	 * @see Result
	 */
	public Result search(String query) throws TimeoutException {
		/* Create channel callback. */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send search query. */
		try{
			this.protocol.sendSearchQuery(callback, query);
		}
		catch(ProtocolException e){
			return null;
		}
		
		/* Get data. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Create result from XML. */
		Result result = XMLMediaParser.parseResult(data, "UTF-8");
		
		result.setQuery(query);
		
		return result;
	}
	
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
	public Image image(String id) throws TimeoutException {
		/* Data buffer. */
		byte[] data;
		
			/* Create channel callback. */
			ChannelCallback callback = new ChannelCallback();
			
			/* Send image request. */
			try{
				this.protocol.sendImageRequest(callback, id);
			}
			catch(ProtocolException e){
				return null;
			}
			
			/* Get data. */
			data = callback.get(this.timeout, this.unit);
			
		/* Create Image. */
		try{
			return ImageIO.read(new ByteArrayInputStream(data));
		}
		catch(IOException e){
			return null;
		}
	}
	
	/**
	 * Browse artist, album or track info.
	 * 
	 * @param type Type of media to browse for.
	 * @param id   A 32-character hex string or a Spotify URI.
	 * 
	 * @return An {@link XMLElement} object holding the data or null
	 *         on failure.
	 * 
	 * @see BrowseType
	 */
	private Object browse(int type, String id) throws TimeoutException {
		/*
		 * Check if id is a 32-character hex string,
		 * if not try to parse it as a Spotify URI.
		 */
		if(id.length() != 32 && !Hex.isHex(id)){
			try{
				Link link = Link.create(id);
				
				if((type == BROWSE_ARTIST && !link.isArtistLink()) ||
				   (type == BROWSE_ALBUM  && !link.isAlbumLink())  ||
				   (type == BROWSE_TRACK  && !link.isTrackLink())){
					throw new IllegalArgumentException(
						"Browse type doesn't match given Spotify URI."
					);
				}
				
				id = link.getId();
			}
			catch(InvalidSpotifyURIException e){
				throw new IllegalArgumentException(
					"Given id is neither a 32-character " +
					"hex string nor a valid Spotify URI."
				);
			}
		}
		
		/* Create channel callback. */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send browse request. */
		try{
			this.protocol.sendBrowseRequest(callback, type, id);
		}
		catch(ProtocolException e){
			return null;
		}
		
		/* Create object from XML. */
		return XMLMediaParser.parse(
			callback.get(this.timeout, this.unit), "UTF-8"
		);
	}
	
	/**
	 * Browse artist info by id.
	 * 
	 * @param id A 32-character hex string or a Spotify URI.
	 * 
	 * @return An {@link Artist} object holding more information about
	 *         the artist or null on failure.
	 * 
	 * @see Artist
	 */
	public Artist browseArtist(String id) throws TimeoutException {
		/* Browse. */
		Object artist = this.browse(BROWSE_ARTIST, id);
		
		if(artist instanceof Artist){
			return (Artist)artist;
		}
		
		return null;
	}
	
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
	public Artist browse(Artist artist) throws TimeoutException {
		return this.browseArtist(artist.getId());
	}
	
	/**
	 * Browse album info by id.
	 * 
	 * @param id A 32-character hex string or a Spotify URI.
	 * 
	 * @return An {@link Album} object holding more information about
	 *         the album or null on failure.
	 * 
	 * @see Album
	 */
	public Album browseAlbum(String id) throws TimeoutException {
		/* Browse. */
		Object album = this.browse(BROWSE_ALBUM, id);
		
		if(album instanceof Album){
			return (Album)album;
		}
		
		return null;
	}
	
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
	public Album browse(Album album) throws TimeoutException {
		return this.browseAlbum(album.getId());
	}
	
	/**
	 * Browse track info by id.
	 * 
	 * @param id A 32-character hex string or a Spotify URI.
	 * 
	 * @return A {@link Track} object or null on failure.
	 * 
	 * @see Track
	 */
	public Track browseTrack(String id) throws TimeoutException {
		/* Browse. */
		Object object = this.browse(BROWSE_TRACK, id);
		
		if(object instanceof Result){
			Result result = (Result)object;
			
			if(result.getTracks().isEmpty()){
				return null;
			}
			
			return result.getTracks().get(0);
		}
		
		return null;
	}
	
	/**
	 * Browse track info.
	 * 
	 * @param track A {@link Track} object identifying the track to browse.
	 * 
	 * @return A {@link Track} object or null on failure.
	 * 
	 * @see Track
	 */
	public Track browse(Track track) throws TimeoutException {
		return this.browseTrack(track.getId());
	}
	
	/**
	 * Browse information for multiple tracks by id.
	 * 
	 * @param ids A {@link List} of ids identifying the tracks to browse.
	 * 
	 * @return A list of {@link Track} objects or null on failure.
	 * 
	 * @see Track
	 */
	public List<Track> browseTracks(List<String> ids) throws TimeoutException {
		/* Data buffer. */
		byte[] data;
		
		/* Create cache hash. */
		StringBuffer hashBuffer = new StringBuffer();
		
		for(int i = 0; i < ids.size(); i++){
			String id = ids.get(i);
			
			/*
			 * Check if id is a 32-character hex string,
			 * if not try to parse it as a Spotify URI.
			 */
			if(id.length() != 32 && !Hex.isHex(id)){
				try{
					Link link = Link.create(id);
					
					if(!link.isTrackLink()){
						throw new IllegalArgumentException(
							"Browse type doesn't match given Spotify URI."
						);
					}
					
					id = link.getId();
					
					/* Set parsed id in list. */
					ids.set(i, id);
				}
				catch(InvalidSpotifyURIException e){
					throw new IllegalArgumentException(
						"Given id is neither a 32-character " +
						"hex string nor a valid Spotify URI."
					);
				}
			}
			
			/* Append id to buffer in order to create a cache hash. */
			hashBuffer.append(id);
		}
		
			/* Create channel callback */
			ChannelCallback callback = new ChannelCallback();
			
			/* Send browse request. */
			try{
				this.protocol.sendBrowseRequest(callback, BROWSE_TRACK, ids);
			}
			catch(ProtocolException e){
				return null;
			}
			
			/* Get data. */
			data = callback.get(this.timeout, this.unit);
			
		/* Create result from XML. */
		return XMLMediaParser.parseResult(data, "UTF-8").getTracks();
	}
	
	/**
	 * Browse information for multiple tracks.
	 * 
	 * @param tracks A {@link List} of {@link Track} objects identifying
	 *               the tracks to browse.
	 * 
	 * @return A {@link Result} object holding more information about
	 *         the tracks or null on failure.
	 * 
	 * @see Track
	 */
	public List<Track> browse(List<Track> tracks) throws TimeoutException {
		/* Create id list. */
		List<String> ids = new ArrayList<String>();
		
		for(Track track : tracks){
			ids.add(track.getId());
		}
		
		return this.browseTracks(ids);
	}
	
	/**
	 * Request a replacement track.
	 * 
	 * @param track The track to search the replacement for.
	 * 
	 * @return A {@link Track} object.
	 * 
	 * @see Track
	 */
	public Track replacement(Track track) throws TimeoutException {
		return this.replacement(Arrays.asList(track)).get(0);
	}
	
	/**
	 * Request multiple replacement track.
	 * 
	 * @param tracks The tracks to search the replacements for.
	 * 
	 * @return A list of {@link Track} objects.
	 * @throws TimeoutException  
	 * 
	 * @see Track
	 */
	public List<Track> replacement(List<Track> tracks) throws TimeoutException {
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send browse request. */
		try{
			this.protocol.sendReplacementRequest(callback, tracks);
		}
		catch(ProtocolException e){
			return null;
		}
		
		/* Get data. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Create result from XML. */
		return XMLMediaParser.parseResult(data, "UTF-8").getTracks();
	}
	
	/**
	 * Get stored user playlists.
	 * 
	 * @return A {@link PlaylistContainer} holding {@link Playlist} objects
	 *         or an empty {@link PlaylistContainer} on failure.
	 *         Note: {@link Playlist} objects only hold id and author and need
	 *         to be loaded using {@link #playlist(String)}.
	 * 
	 * @throws TimeoutException  
	 * 
	 * @see PlaylistContainer
	 */
	public PlaylistContainer playlistContainer() throws TimeoutException {
		/* Create channel callback. */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send request and parse response. */
		try{
			this.protocol.sendPlaylistRequest(callback, null);
			
			/* Create and return playlist. */
			return XMLPlaylistParser.parsePlaylistContainer(
				callback.get(this.timeout, this.unit), "UTF-8"
			);
		}
		catch(ProtocolException e){
			return PlaylistContainer.EMPTY;
		}
	}
	
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
	public Playlist playlist(String id, boolean cached) throws TimeoutException {
		/*
		 * Check if id is a 32-character hex string,
		 * if not try to parse it as a Spotify URI.
		 */
		if(id.length() != 32 && !Hex.isHex(id)){
			try{
				Link link = Link.create(id);
				
				if(!link.isPlaylistLink()){
					throw new IllegalArgumentException(
						"Given Spotify URI is not a playlist URI."
					);
				}
				
				id = link.getId();
			}
			catch(InvalidSpotifyURIException e){
				throw new IllegalArgumentException(
					"Given id is neither a 32-character " +
					"hex string nor a valid Spotify URI."
				);
			}
		}
		
		/* Data buffer. */
		byte[] data;
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
			
		/* Send playlist request. */
		try{
			this.protocol.sendPlaylistRequest(callback, id);
		}
		catch(ProtocolException e){
			return null;
		}
			
		/* Get data. */
		data = callback.get(this.timeout, this.unit);
		
		/* Create and return playlist. */
		return XMLPlaylistParser.parsePlaylist(data, "UTF-8", id);
	}
	
	/**
	 * Get a playlist.
	 * 
	 * @param id Id of the playlist to load.
	 * 
	 * @return A {@link Playlist} object or null on failure.
	 * 
	 * @see Playlist
	 */
	public Playlist playlist(String id) throws TimeoutException {
		return this.playlist(id, false);
	}
	
	/**
	 * Handles incoming commands from the server.
	 * 
	 * @param command A command.
	 * @param payload Payload of packet.
	 */
	public void commandReceived(int command, byte[] payload){
		//System.out.format("< Command: 0x%02x Length: %d\n", command, payload.length);
		
		switch(command){
			case Command.COMMAND_SECRETBLK: {
				/* Check length. */
				if(payload.length != 336){
					System.err.format("Got command 0x02 with len %d, expected 336!\n", payload.length);
				}
				
				/* Check RSA public key. */
				byte[] rsaPublicKey = RSA.keyToBytes(this.session.getRSAPublicKey());
				
				for(int i = 0; i < 128; i++){
					if(payload[16 + i] != rsaPublicKey[i]){
						System.err.format("RSA public key doesn't match! %d\n", i);
						
						break;
					}
				}
				
				/* Send cache hash. */
				try{
					this.protocol.sendCacheHash();
				}
				catch(ProtocolException e){
					/* Just don't care. */
				}
				
				break;
			}
			case Command.COMMAND_PING: {
				/* Ignore the timestamp but respond to the request. */
				/* int timestamp = IntegerUtilities.bytesToInteger(payload); */
				try{
					this.protocol.sendPong();
				}
				catch(ProtocolException e){
					/* Just don't care. */
				}
				
				break;
			}
			case Command.COMMAND_PONGACK: {
				break;
			}
			case Command.COMMAND_CHANNELDATA: {
				Channel.process(payload);
				
				break;
			}
			case Command.COMMAND_CHANNELERR: {
				Channel.error(payload);
				
				break;
			}
			case Command.COMMAND_AESKEY: {
				/* Channel id is at offset 2. AES Key is at offset 4. */
				Channel.process(Arrays.copyOfRange(payload, 2, payload.length));
				
				break;
			}
			case Command.COMMAND_SHAHASH: {
				/* Do nothing. */
				break;
			}
			case Command.COMMAND_COUNTRYCODE: {
				//System.out.println("Country: " + new String(payload, Charset.forName("UTF-8")));
				this.user.setCountry(new String(payload, Charset.forName("UTF-8")));
				
				/* Release 'country' permit. */
				this.userSemaphore.release();
				
				break;
			}
			case Command.COMMAND_P2P_INITBLK: {
				/* Do nothing. */
				break;
			}
			case Command.COMMAND_NOTIFY: {
				/* HTML-notification, shown in a yellow bar in the official client. */
				/* Skip 11 byte header... */
				System.out.println("Notification: " + new String(
					Arrays.copyOfRange(payload, 11, payload.length), Charset.forName("UTF-8")
				));
				this.user.setNotification(new String(
					Arrays.copyOfRange(payload, 11, payload.length), Charset.forName("UTF-8")
				));
				
				break;
			}
			case Command.COMMAND_PRODINFO: {
				this.user = XMLUserParser.parseUser(payload, "UTF-8", this.user);
				
				/* Release 'prodinfo' permit. */
				this.userSemaphore.release();
				
				break;
			}
			case Command.COMMAND_WELCOME: {
				break;
			}
			case Command.COMMAND_PAUSE: {
				/* TODO: Show notification and pause. */
				break;
			}
			case Command.COMMAND_PLAYLISTCHANGED: {
				System.out.format("Playlist '%s' changed!\n", Hex.toHex(payload));
				
				break;
			}
			default: {
				/*
				System.out.format("Unknown Command: 0x%02x Length: %d\n", command, payload.length);
				System.out.println("Data: " + new String(payload));// + " " + Hex.toHex(payload));
				*/
				// No mostramos nada silenciosamente. No es necesario mostrar datos de depuracion en un entorno normal
				break;
			}
		}
	}
}
