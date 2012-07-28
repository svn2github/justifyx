/*
    This file is part of Justify.

    Justify is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Justify is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.denibol.justify;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import static org.kohsuke.args4j.ExampleMode.REQUIRED;

import de.felixbruns.jotify.JotifyConnection;
import de.felixbruns.jotify.exceptions.AuthenticationException;
import de.felixbruns.jotify.exceptions.ConnectionException;
import de.felixbruns.jotify.media.Album;
import de.felixbruns.jotify.media.Link;
import de.felixbruns.jotify.media.Playlist;
import de.felixbruns.jotify.media.Result;
import de.felixbruns.jotify.media.Track;
import de.felixbruns.jotify.media.User;
import de.felixbruns.jotify.media.Link.InvalidSpotifyURIException;
import de.felixbruns.jotify.player.SpotifyInputStream;

import adamb.vorbis.CommentField;
import adamb.vorbis.VorbisCommentHeader;
import adamb.vorbis.VorbisIO;

public class Justify extends JotifyConnection{
	private static Pattern REGEX = Pattern.compile(":(.*?):");
	private static String ALBUM_FORMAT = ":artist.name: - :name:";
	private static String PLAYLIST_FORMAT = ":author: - :name:";
	private static Integer discindex = 1;
	private static Integer oldtracknumber = 1;
	private static String country;
	
    @Option(name="-user", metaVar = "<spotify_user>", usage="Spotify Premium username (required)", required=true)
    private static String user;
    
    @Option(name="-password", metaVar = "<spotify_password>", usage="Spotify user password (required)", required=true)
    private static String password;
    
    @Option(name="-cover", metaVar = "<spotifyURI>", usage="Downloads album cover")
    private static String coverURI;

    @Option(name="-download", metaVar ="<spotifyURI>", usage="Downloads track/list/album")
    private static String downloadURI;
    
    @Option(name="-number", metaVar ="<song_number>", usage="Downloads starting on the specified track number. Requires -download")
    private static int songnumber;
    
    @Option(name="-codec", metaVar ="<format>", usage="Specify codec and bitrate of the download. Options:\n    ogg_96: Ogg Vorbis @ 96kbps\n    ogg_160: Ogg Vorbis @ 160kbps\n    ogg_320: Ogg Vorbis @ 320kbps")
    private static String formataudio = "ogg_320";
    
    @Option(name="-toplist", metaVar ="<type>", usage="Downloads toplist tracks/albums/artists.\n    track: tracks toplist")
    private static String toplist_type = "track";
    
    @Option(name="-toplist-region", metaVar ="<region>", usage="Specify region of toplist to download.\nNot specified: default region of the user.\n    region (2 letters): a specified region.\n    ALL: all regions toplist")
    private static String toplist_region;

    @Option(name="-oggcover", metaVar ="<method>", usage="Method to embed cover in ogg file. Options:\n    new: new method (METADATA_BLOCK_PICTURE)\n    old: old method (default, COVERART and COVERARTMIME)\n    none: not embed cover in ogg")
    private static String oggcover = "old";
 
    @Option(name="-timeout", metaVar ="<seconds>", usage="Number of seconds before throwing a timeout (default: 20 seconds)")
    private static long TIMEOUT = 20;
    
    @Option(name="-chunksize", metaVar ="<bytes>", usage="Fixed chunk size (default: 4096 bytes)")
    private static int chunksize = 4096;

    @Option(name="-substreamsize", metaVar ="<bytes>", usage="Fixed substream size (default: 30seconds of 320kbps audio data (320 * 1024 * 30 / 8) = 1228800 bytes)")
    private static int substreamsize = 320 * 1024 * 30 / 8;
    
    // receives other command line parameters than options
    @Argument
    private List<String> arguments = new ArrayList<String>();
	
	public static void main(String args[]) throws IOException, InterruptedException{
		
	    new Justify().doMain(args);
		
		if(coverURI==null && downloadURI==null && toplist_type==null) {
			System.err.println();
			System.err.println("[ERROR] Needs something to download");
			return;
		}
	    
		Justify justify = new Justify();
		try{

			try{ justify.login(user, password);
			}catch(ConnectionException ce){ throw new JustifyException("[ERROR] Error connecting the server");
			}catch(AuthenticationException ae){ throw new JustifyException("[ERROR] User or password is not valid"); }
			
			User usuario = justify.user();
			country = usuario.getCountry();
			System.out.println(usuario);
			System.out.println();
			if (!usuario.isPremium()) throw new JustifyException("[ERROR] You must be a 'premium' user");
			
			if(toplist_region==null) toplist_region=country;
			
			try{
				Link uri = null;
				
				if(downloadURI!=null)
					uri = Link.create(downloadURI);
				else if(coverURI!=null)
					uri = Link.create(coverURI);

				// Toplist command
				if(downloadURI==null && coverURI==null) {
					Result result = justify.toplist(toplist_type, toplist_region.equals("ALL") ? null : toplist_region, null);
					Date now = new Date();
					SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
					String directorio = sdf.format(now) + " toplist-" + toplist_type + "-" + toplist_region;
					Integer indextoplist = 1;
					System.out.println("Toplist: " + toplist_type + " | Region: " + toplist_region + " | Tracks: " + result.getTotalTracks());
					System.out.println();
					
					if(toplist_type.equals("track")) {
						for(Track track : result.getTracks()) {
							justify.downloadTrack(justify.browse(track), directorio, formataudio, "playlist", indextoplist);
							indextoplist++;
						}
					}
				}
				
				// Download command
				else if(downloadURI!=null) {
					
					// Download track command
					if (uri.isTrackLink()){
						Track track = justify.browseTrack(uri.getId());
						if (track == null) throw new JustifyException("[ERROR] Track not found");
						justify.downloadTrack(track, null, formataudio, "track", 0);
					}
					
					// Download playlist command
					else if (uri.isPlaylistLink()){
						Playlist playlist = justify.playlist(uri.getId());
						if (playlist == null) throw new JustifyException("[ERROR] Playlist not found");
						System.out.println("Playlist: " + playlist.getName() + " | Author: " + playlist.getAuthor() + " | Tracks: " + playlist.getTracks().size());
						System.out.println();
						String directorio = replaceByReference(playlist, PLAYLIST_FORMAT);
						DecimalFormat f = new DecimalFormat( "00" );
						Integer indexplaylist = 1;
						for(Track track : playlist.getTracks()) {
							System.out.print("[" + f.format((indexplaylist - 1) * 100 / playlist.getTracks().size()) + "%] ");							
							justify.downloadTrack(justify.browse(track), directorio, formataudio, "playlist", indexplaylist);
							indexplaylist++;
						}
						indexplaylist = 0;
						System.out.println("[100%] Playlist downloaded");
					}
					
					// Download album command
					else if(uri.isAlbumLink()){
							Album album = justify.browseAlbum(uri.getId());
							if (album == null) throw new JustifyException("[ERROR] Album not found");
							System.out.println("Album: " + album.getName() + " | Artist: " + album.getArtist().getName() + " | Tracks: " + album.getTracks().size() +" | Discs: " + album.getDiscs().size());
							System.out.println();
							String directorio = replaceByReference(album, ALBUM_FORMAT);
							for(Track track : album.getTracks()){
								if (songnumber == 0 || track.getTrackNumber() >= songnumber)
									justify.downloadTrack(track, directorio, formataudio, "album", 0);
							}
							justify.downloadCover(justify.image(album.getCover()), directorio);			
					} else throw new JustifyException("[ERROR] Track, album or playlist not specified");
				}
				
				// Cover command
				else if(coverURI!=null){
					if(uri.isAlbumLink()){
						Album album = justify.browseAlbum(uri.getId());
						if (album == null) throw new JustifyException("[ERROR] Album not found");
						System.out.println("Album: " + album.getName() + " | Artist: " + album.getArtist().getName());
						System.out.println();
						String directorio = replaceByReference(album, ALBUM_FORMAT);
						justify.downloadCover(justify.image(album.getCover()), directorio);			
					}
				}
			}catch (InvalidSpotifyURIException urie){ throw new JustifyException("[ERROR] Spotify URI is not valid"); }
				
		}catch (JustifyException je){ System.err.println(je.getMessage()); je.printStackTrace();
		}catch (TimeoutException te){ System.err.println(te.getMessage()); te.printStackTrace();
		}finally{
			try{ justify.close();
			}catch (ConnectionException ce){ System.err.println("[ERROR] Problem disconnecting"); } 
		}
	}
	
    public void doMain(String[] args) throws IOException {
        CmdLineParser parser = new CmdLineParser(this);
        
        parser.setUsageWidth(120);

        try {
            parser.parseArgument(args);
        } catch( CmdLineException e ) {
            // if there's a problem in the command line,
            // you'll get this exception. this will report
            // an error message.
            System.err.println("java -jar justifyx.jar [options...]");
            // print the list of available options
            parser.printUsage(System.err);
            System.err.println();
            System.err.println("[ERROR] " + e.getMessage());
            System.err.println();

            // print option sample. This is useful some time
            System.err.println("Example: java -jar justifyx.jar"+ parser.printExample(REQUIRED) + " -download <spotifyURI>");

            return;
        }
    }
    
	public Justify(){ super(TIMEOUT, TimeUnit.SECONDS); }
	
	private void downloadCover(Image image, String parent) throws TimeoutException, IOException {
		Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("jpeg");
		ImageWriter writer = (ImageWriter)iter.next();
		ImageWriteParam iwp = writer.getDefaultWriteParam();
		iwp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		iwp.setCompressionQuality(1);
		java.io.File coverfile = new java.io.File(sanearNombre(parent), "folder.jpg");
		coverfile.getParentFile().mkdirs();
		FileImageOutputStream output = new FileImageOutputStream(coverfile);
		writer.setOutput(output);
		IIOImage iimage = new IIOImage((BufferedImage) image, null, null);
		writer.write(null, iimage, iwp);
		writer.dispose();
		System.out.println("[100%] Album cover  <-  OK!");
	}

	private void downloadTrack(Track track, String parent, String bitrate, String option, Integer indexplaylist) throws JustifyException, TimeoutException{	

		// Downloading an album, if the new track number is lower than the previous downloaded song, it means we are in a new disc
		if(option.equals("album")) {
			if(track.getTrackNumber() < oldtracknumber) {
				discindex++;
				oldtracknumber = 1;
			} else oldtracknumber = track.getTrackNumber();
		}
		
		try{
			String filename="";
			DecimalFormat f = new DecimalFormat( "00" );
			
			// Filename: sets the numbering of the track if it is an album / playlist
			if(option.equals("album")) {
				// Numbering of filename when downloads an album. numbering = "[number_of_disc] + number_of_track" : e.g. "101"
				filename = (track.getAlbum().getDiscs().size() > 1 ? discindex : "") + (track.getTrackNumber() < 10 ? "0" : "") + track.getTrackNumber() + " ";
			}
			else if (option.equals("playlist")) {
				// Numbering of filename when downloads a playlist. numbering = "indexplaylist" : e.g. "01"
				filename = (indexplaylist < 10 ? "0" : "") + indexplaylist.toString() + " ";
			}
			
			// Filename: sets the final name to "numbering Album_Artist - Track_Title.ogg"
			filename =  filename + track.getAlbum().getArtist().getName() + " - " + track.getTitle() + ".ogg";
			java.io.File file = new java.io.File(sanearNombre(parent), sanearNombre(filename));

			// Prints filename and with a progress percentage if downloading an album
			if(option.equals("track") || option.equals("playlist"))
				System.out.print(sanearNombre(filename));
			else if (option.equals("album"))
				System.out.print("[" + f.format((track.getTrackNumber() - 1) * 100 / track.getAlbum().getTracks().size()) + "%] " + sanearNombre(filename));
			
			System.out.print(" ");
			// Create directory
			if(parent != null && !file.getParentFile().exists()) file.getParentFile().mkdirs();

			// Check restrictions and parse alternative files checking their restrictions
			boolean allowed = true;
			Integer nalternative = 0;
            Integer talternative = 0;
            
            if(track.getRestrictions().get(0).getForbidden() != null)
                    if(track.getRestrictions().get(0).getForbidden().contains(country) == true)
                            allowed = false;
                                    
            if(track.getRestrictions().get(0).getAllowed() != null)
                    if (track.getRestrictions().get(0).getAllowed().contains(country) == false)
                            allowed = false;
            
            if (allowed == false) {
                    for(Track pista : track.getAlternatives()) {
                            nalternative++;
                            if(pista.getRestrictions().get(0).getForbidden() != null)
                                    if(pista.getRestrictions().get(0).getForbidden().contains(country) == true)
                                            allowed = false;
                                    else {
                                    	allowed = true;
                                    	talternative++;
                                    }
                            
                            if(pista.getRestrictions().get(0).getAllowed() != null)
                                    if (pista.getRestrictions().get(0).getAllowed().contains(country) == true) {
                                            allowed = true;
                                            talternative = nalternative;
                                    }
                            
                            //Map<String,String> exids;
                    }
            }

            if (allowed && nalternative == 0) download(track, file, bitrate);
            else {
                    if (allowed && nalternative > 0 ) download(track.getAlternatives().get(talternative-1), file, bitrate); else {
                    	System.out.println("-- ko!");
                    	return;
                    }
            }

			try {
				VorbisCommentHeader comments = new VorbisCommentHeader();
				
				// Embeds cover in .ogg for tracks and playlists (not albums)
				if((option.equals("track") || option.equals("playlist")) && (!oggcover.equals("none"))) {
					byte[] imagedata = null;
					try{
						BufferedImage image = (BufferedImage) this.image(track.getCover());
						ByteArrayOutputStream output = new ByteArrayOutputStream();
						ImageIO.write(image, ImageFormats.getFormatForMimeType(ImageFormats.MIME_TYPE_JPG), new DataOutputStream(output));
						imagedata = output.toByteArray();
					}catch(Exception e) { e.printStackTrace(); }
					if (imagedata != null){
						char[] testdata = Base64Coder.encode(imagedata);
						String base64image = new String(testdata);
						//doc: embedded artwork vorbis standards: http://wiki.xiph.org/VorbisComment#Cover_art
						if(oggcover.equals("old")){
							comments.fields.add(new CommentField("COVERART",base64image));
							comments.fields.add(new CommentField("COVERARTMIME",ImageFormats.MIME_TYPE_JPG));
						} else if(oggcover.equals("new")){
							comments.fields.add(new CommentField("METADATA_BLOCK_PICTURE",base64image));
						}
					}				
				}
				
				comments.fields.add(new CommentField("ARTIST", track.getArtist().getName()));
				comments.fields.add(new CommentField("ALBUM ARTIST", track.getAlbum().getArtist().getName()));
				comments.fields.add(new CommentField("ALBUM", track.getAlbum().getName()));
				comments.fields.add(new CommentField("TITLE", track.getTitle()));
				comments.fields.add(new CommentField("DATE", String.valueOf(track.getYear())));
				
				// Sets track_number to real track number except in playlists
				if(option.equals("playlist"))
					comments.fields.add(new CommentField("TRACKNUMBER", indexplaylist.toString()));
				else
					comments.fields.add(new CommentField("TRACKNUMBER", String.valueOf(track.getTrackNumber())));
				
				// Sets disc_number and total_discs only when downloading an album
				if (option.equals("album")) {
					comments.fields.add(new CommentField("DISCNUMBER", discindex.toString()));
					comments.fields.add(new CommentField("TOTALDISCS", String.valueOf(track.getAlbum().getDiscs().size())));
				}
				
				VorbisIO.writeComments(file, comments);
			} catch (IOException e) { e.printStackTrace(); }
			
			System.out.println(" ok");
		}catch(FileNotFoundException fnfe){ fnfe.printStackTrace();
		}catch(IOException ioe){ ioe.printStackTrace();
		}
	}

	private void download(Track track, java.io.File file, String bitrate) throws TimeoutException, IOException{
		if (track.getFiles().size() == 0) return;
		FileOutputStream fos = new FileOutputStream(file);
		SpotifyInputStream sis = new SpotifyInputStream(protocol, track, bitrate, chunksize, substreamsize);

		System.out.print(".");

		int counter = 0;
		byte[] buf = new byte[8192];
		sis.read(buf, 0, 167); // Skip Spotify OGG Header

		while (true) {
			counter++;
			int length = sis.read(buf);
			if (length < 0) break;
			fos.write(buf, 0, length);
			if(counter==256) {
				counter = 0;
				System.out.print(".");
			}
		}
		
		sis.close();
		fos.close();
	}

	public static boolean isWindows(){
		String os = System.getProperty("os.name").toLowerCase();
		return (os.indexOf( "win" ) >= 0); 
	}

	public static String sanearNombre(String nombre){
		if(nombre==null) return null;

		if(isWindows())
			nombre = nombre.replaceAll("\\\\", "/");
		else
			nombre = nombre.replaceAll("/", "\\\\");

		nombre = nombre.replaceAll("[\\\\/:*?\"<>|]", "_");

		return nombre;
	}

	public static String capitalize(String s) { return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase(); }

	public static String replaceByReference(Object inicial, String formato) throws JustifyException{
		StringBuffer resultString = new StringBuffer();
		try {

			Matcher regexMatcher = REGEX.matcher(formato);
			while (regexMatcher.find()) {
				String referencias = regexMatcher.group();
				referencias = referencias.substring(1, referencias.length() - 1);
				String[] metodos = referencias.split("\\.");
				Object objeto = inicial;
				for (String metodo : metodos){

					Class<?> clase = objeto.getClass();
					Method method = clase.getMethod("get" + capitalize(metodo), new Class<?>[0]);
					objeto = method.invoke(objeto, new Object[0]);
				}

				regexMatcher.appendReplacement(resultString, (String)objeto);
			}
			regexMatcher.appendTail(resultString);
		} catch (Exception e){ throw new JustifyException(e); }
		return resultString.toString();
    }	
}
