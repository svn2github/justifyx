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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

import de.felixbruns.jotify.JotifyConnection;
import de.felixbruns.jotify.exceptions.AuthenticationException;
import de.felixbruns.jotify.exceptions.ConnectionException;
import de.felixbruns.jotify.media.Album;
import de.felixbruns.jotify.media.Link;
import de.felixbruns.jotify.media.Playlist;
import de.felixbruns.jotify.media.Track;
import de.felixbruns.jotify.media.User;
import de.felixbruns.jotify.media.Link.InvalidSpotifyURIException;
import de.felixbruns.jotify.player.SpotifyInputStream;

import adamb.vorbis.CommentField;
import adamb.vorbis.VorbisCommentHeader;
import adamb.vorbis.VorbisIO;

import org.farng.mp3.MP3File;
import org.farng.mp3.TagConstant;
import org.farng.mp3.TagException;
import org.farng.mp3.TagOptionSingleton;
import org.farng.mp3.id3.*;

public class Justify extends JotifyConnection{
	private static Pattern REGEX = Pattern.compile(":(.*?):");
	private static String TRACK_FORMAT = ":artist.name: - :title:.";
	private static String ALBUM_FORMAT = ":artist.name: - :name:";
	private static String PLAYLIST_FORMAT = ":author: - :name:";
	private static long TIMEOUT = 10; // en segundos
	private static Integer discindex = 1;
	private static Integer oldtracknumber = 1;
	private static String user;
	private static String password;
	static String formataudio;
	static String commandarg;
	static String numbersong;
	static String country;
	
	public static void main(String args[]) throws IOException{
		
		if (args.length < 5 || args.length > 6 ){
			System.err.println("[ERROR] Parameters: Spotify_user Spotify_password Spotify_URI Format Command");
			System.err.println("Format:");
			System.err.println("    ogg_96:  Ogg Vorbis @ 96kbps");
			System.err.println("    ogg_160: Ogg Vorbis @ 160kbps");
			System.err.println("    ogg_320: Ogg Vorbis @ 320kbps");
			System.err.println("    mp3_320: MP3 @ 320kbps");
			System.err.println("Command:");
			System.err.println("    download: downloads track/list/album");
			System.err.println("    download number: downloads an album starting on the specified track number");
			System.err.println("    cover: downloads album cover");
			return;
		}
		
		user = args[0];
		password = args[1];
		formataudio = args[3];
		commandarg = args[4];
		if (args.length == 6) numbersong = args[5];
		
		Justify justify = new Justify();
		try{
			try{ justify.login(user, password);
			}catch(ConnectionException ce){ throw new JustifyException("[ERROR] No se ha podido conectar con el servidor");
			}catch(AuthenticationException ae){ throw new JustifyException("[ERROR] Usuario o password no validos"); }

			User usuario = justify.user();
			country = usuario.getCountry();
			System.out.println(usuario);
			System.out.println();
			if (!usuario.isPremium()) throw new JustifyException("[ERROR] Debes ser usuario 'premium'");
			try{
				Link uri = Link.create(args[2]);

				if(commandarg.equals("download")) {
					if (uri.isTrackLink()){
						
						Track track = justify.browseTrack(uri.getId());
						if (track == null) throw new JustifyException("[ERROR] Pista no encontrada");
						justify.downloadTrack(track, null, formataudio);
						
					}else if (uri.isPlaylistLink()){
						
						Playlist playlist = justify.playlist(uri.getId());
						if (playlist == null) throw new JustifyException("[ERROR] Lista de reproduccion no encontrada");
						System.out.println(playlist);
						System.out.println("Numero de pistas: " + playlist.getTracks().size());
						String directorio = replaceByReference(playlist, PLAYLIST_FORMAT);
						for(Track track : playlist.getTracks()) justify.downloadTrack(justify.browse(track), directorio, formataudio);
						
					}else if(uri.isAlbumLink()){
							Album album = justify.browseAlbum(uri.getId());
							if (album == null) throw new JustifyException("[ERROR] Album no encontrado");
							System.out.println("Album: " + album.getName() + " | Artist: " + album.getArtist().getName() + " | Tracks: " + album.getTracks().size() +" | Discs: " + album.getDiscs().size());
							System.out.println();
							String directorio = replaceByReference(album, ALBUM_FORMAT);
							if(args.length == 5) { 
								for(Track track : album.getTracks()) justify.downloadTrack(track, directorio, formataudio);
							} else if(args.length == 6) {
								for(Track track : album.getTracks()){
									if (track.getTrackNumber() >= Integer.parseInt(numbersong))
										justify.downloadTrack(track, directorio, formataudio);								
								}
							}
							justify.downloadCover(justify.image(album.getCover()), directorio);			
					} else throw new JustifyException("[ERROR] Se esperaba una pista, album o lista de reproduccion");
				} else if(commandarg.equals("cover")){
					if(uri.isAlbumLink()){
						Album album = justify.browseAlbum(uri.getId());
						if (album == null) throw new JustifyException("[ERROR] Album no encontrado");
						System.out.println("Album: " + album.getName() + " | Artist: " + album.getArtist().getName());
						System.out.println();
						String directorio = replaceByReference(album, ALBUM_FORMAT);
						justify.downloadCover(justify.image(album.getCover()), directorio);			
					}
				}
			}catch (InvalidSpotifyURIException urie){ throw new JustifyException("[ERROR] Direccion de Spotify no valida"); }
				
		}catch (JustifyException je){ System.err.println(je.getMessage()); je.printStackTrace();
		}catch (TimeoutException te){ System.err.println(te.getMessage()); te.printStackTrace();
		}finally{
			try{ justify.close();
			}catch (ConnectionException ce){ System.err.println("[ERROR] No se ha podido desconectar"); } 
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
		FileImageOutputStream output = new FileImageOutputStream(coverfile);
		writer.setOutput(output);
		IIOImage iimage = new IIOImage((BufferedImage) image, null, null);
		writer.write(null, iimage, iwp);
		writer.dispose();
		System.out.println("[100%] Album cover  <-  OK!");
		System.out.println();
	}

	private void downloadTrack(Track track, String parent, String bitrate) throws JustifyException, TimeoutException{	
		if(track.getAlbum().getDiscs().size() > 1) {
			if(track.getTrackNumber() < oldtracknumber) {
				discindex++;
				oldtracknumber = 1;
			} else oldtracknumber = track.getTrackNumber();
		}
		
		try{
			String nombre = (track.getAlbum().getDiscs().size() > 1 ? discindex : "") + (track.getTrackNumber() < 10 ? "0" : "") + track.getTrackNumber() + " " + track.getArtist().getName() + " - " + track.getTitle() + (bitrate.contains("ogg") == true ? ".ogg" : ".mp3");
			java.io.File file = new java.io.File(sanearNombre(parent), sanearNombre(nombre));
			DecimalFormat f = new DecimalFormat( "###.#" );
			System.out.print("[" + f.format((track.getTrackNumber() - 1) * 100 / track.getAlbum().getTracks().size()) + "%] " + sanearNombre(nombre));
			if(parent != null && !file.getParentFile().exists()) file.getParentFile().mkdirs();

			boolean allowed = true;
			
			if(track.getRestrictions().get(0).getForbidden() != null)
				if(track.getRestrictions().get(0).getForbidden().contains(country) == true)
					allowed = false;
			
			if(track.getRestrictions().get(0).getAllowed() != null)
				if (track.getRestrictions().get(0).getAllowed().contains(country) == false)
					allowed = false;
			
			if (allowed) download(track, file, bitrate);
			else {
				System.out.println("  <-  KO! Region " + country + " not allowed");
				return;
			}

			if (bitrate.contains("ogg")) {
				try {
					VorbisCommentHeader comments = new VorbisCommentHeader();
					comments.fields.add(new CommentField("ARTIST", track.getArtist().getName()));
					comments.fields.add(new CommentField("ALBUM ARTIST", track.getAlbum().getArtist().getName()));
					comments.fields.add(new CommentField("ALBUM", track.getAlbum().getName()));
					comments.fields.add(new CommentField("TITLE", track.getTitle()));
					comments.fields.add(new CommentField("DATE", String.valueOf(track.getYear())));
					comments.fields.add(new CommentField("TRACKNUMBER", String.valueOf(track.getTrackNumber())));
					comments.fields.add(new CommentField("DISCNUMBER", discindex.toString()));
					comments.fields.add(new CommentField("TOTALDISCS", String.valueOf(track.getAlbum().getDiscs().size())));
					VorbisIO.writeComments(file, comments);
				} catch (IOException e) { e.printStackTrace(); }
			} else if (bitrate.contains("mp3")) {
		        MP3File mp3file = new MP3File();
		        mp3file.setMp3file(file);
		        TagOptionSingleton.getInstance().setDefaultSaveMode(TagConstant.MP3_FILE_SAVE_OVERWRITE);
		        ID3v2_3 tag = new ID3v2_3(mp3file.getID3v2Tag());
		        tag.setSongTitle(track.getTitle());
		        tag.setLeadArtist(track.getArtist().getName());
		        tag.setAlbumTitle(track.getAlbum().getName());
		        tag.setYearReleased(String.valueOf(track.getYear()));
		        tag.setTrackNumberOnAlbum(String.valueOf(track.getTrackNumber()));
		        mp3file.setID3v2Tag(tag);
		        mp3file.save();
		        java.io.File filetmp = new java.io.File(sanearNombre(parent), sanearNombre((track.getAlbum().getDiscs().size() > 1 ? discindex : "") + (track.getTrackNumber() < 10 ? "0" : "") + track.getTrackNumber() + " " + track.getArtist().getName() + " - " + track.getTitle() + "original.mp3"));
		        filetmp.delete();
			}
			
			System.out.println("  <-  OK!");
		}catch(FileNotFoundException fnfe){ fnfe.printStackTrace(); /* throw new JustifyException("[ERROR] No se ha podido guardar el archivo"); */
		}catch(IOException ioe){ ioe.printStackTrace(); /* throw new JustifyException("[ERROR] Ha ocurrido un fallo de entrada / salida"); */
		}catch(TagException e) { e.printStackTrace();
		}
	}

	private void download(Track track, java.io.File file, String bitrate) throws TimeoutException, IOException{
		if (track.getFiles().size() == 0) return;
		FileOutputStream fos = new FileOutputStream(file);
		SpotifyInputStream sis = new SpotifyInputStream(protocol, track, bitrate);

		byte[] buf = new byte[8192];
		if(bitrate.contains("ogg"))
			sis.read(buf, 0, 167); // Skip Spotify OGG Header, no se puede usar skip() porque aun no hay datos leidos por ningun read()
		else
			sis.read(buf, 0, 0);
		while (true) {
			int length = sis.read(buf);
			if (length < 0) break;
			fos.write(buf, 0, length);
		}
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
