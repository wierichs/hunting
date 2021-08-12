<?php
/******************************************************************************
Parameter:
	revier=[Reviername] (*.gpx Datei ohne Endung)
	map=[Kartenansicht] (Default ist "OSM_Landscape")
			Mögliche Ansichten:
			Karte					==> Google Straßenkarte
			Satellit					==> Google Satelitenkarte
			Hybrid	 				==> Google Satelitenkarte mit Label
			Oberflaeche			==> Google Geländekarte
			OSM						==> "OSM Straßenkarte",
			OSM_Cycle			==> "OSM Fahrradkarte",
			OSM_Landscape	==>  "OSM Geländekarte"
Aufruf:	index.php?revier=[Reviername]
HINWEIS: Jagdeinrichtungen müssen in der *.gpx Datei manuell eingetragen werden.
******************************************************************************/
session_start();
if (!isset($_SESSION['revier'])) {
	$_SESSION['revier'] = $_REQUEST["revier"];
}

$gpxfolder = "./reviere/";
if(!$mapview = $_REQUEST["map"]) $mapview = "OSM DE";

if(!$revier = $_REQUEST["revier"]) {
	if(!$act = $_REQUEST["act"]) {
		echo "<!DOCTYPE html> 
				<html> 
					<head> 
						<meta charset=\"utf-8\">
						<title>Revierübersicht</title>
					</head>
					<body>
				   <h1>Revierübersicht</h1>
				   <ul>\n";
		$d = dir($gpxfolder);
		while (false !== ($entry = $d->read())) {
			$file_info = pathinfo($entry);
			if(strtolower($file_info["extension"]) == "gpx") {
				if(strtolower(substr($entry, -8, 4)) != "user") {
					echo "<li><a href=\"".$_SERVER['PHP_SELF']."?revier=".$file_info["filename"]."\">".$file_info["filename"]."</a></li>\n";
				}
			}
		}
		$d->close();
		echo "</ul>\n";
		echo "<p>
					<form enctype=\"multipart/form-data\" action=\"".$_SERVER['PHP_SELF']."\" method=\"POST\">
						<!-- MAX_FILE_SIZE must precede the file input field -->
						<input type=\"hidden\" name=\"MAX_FILE_SIZE\" value=\"1000000\" />
						<input type=\"hidden\" name=\"act\" value=\"upload\" />
						<!-- Name of input element determines name in $_FILES array -->
						Send this file: <input name=\"userfile\" type=\"file\" />
						<input type=\"submit\" value=\"Send File\" />
					</form>
					</p>
					<p>Eine neue Revierkarte kann auch leicht über <a href=\"http://www.gpsies.com/createTrack.do\" target=\"_blank\">www.gpsies.com</a> erstellt werden.</p>
				</body>
			</html>\n";
	}
	else {
		switch($act) {
			case "upload":
				print_r($_FILES['userfile']);
				if (move_uploaded_file($_FILES['userfile']['tmp_name'], $gpxfolder.$_FILES['userfile']['name'])) {
					$file_info = pathinfo($_FILES['userfile']['name']);
					header("Location: ".$_SERVER['PHP_SELF']."?revier=".$file_info["filename"]);
					//echo "File is valid, and was successfully uploaded.\n";
				}
				break;
		}
	}
}
else {

	$addParameter = "";
	if($user = $_REQUEST["user"]) {
		$addParameter .= "&user=".$user;
		if($_REQUEST["lat"]) $addParameter .= "&lat=".$_REQUEST["lat"];
		if($_REQUEST["lon"]) $addParameter .= "&lon=".$_REQUEST["lon"];
		if($_REQUEST["ele"]) {
			$addParameter .= "&alt=".$_REQUEST["ele"];
		}
		else if($_REQUEST["alt"]) {
			$addParameter .= "&alt=".$_REQUEST["alt"];
		}
		if($_REQUEST["cmt"]) $addParameter .= "&cmt=".$_REQUEST["cmt"];
		if($_REQUEST["desc"]) $addParameter .= "&desc=".$_REQUEST["desc"];
	}
	echo "<!DOCTYPE html> 
				<html> 
					<head> 
						<meta charset=\"utf-8\">
						<meta name=\"viewport\" content=\"width=1024\" />
						<title>".$revier."</title>
						<script type=\"text/javascript\" src=\"GM_Utils/GPX2GM.js\"></script>
						<style type=\"text/css\">
							  html, body { height:100% }
								.text { display:inline-block;vertical-align:top;padding-right:2em;max-width:35% }
								.map { width:60%;height:80%;display:inline-block }
						</style>
					</head>
					<body>
						<h1>".$revier."</h1>
						<div class=\"map gpxview:gpx.php?gpx=".$revier.$addParameter.":".$mapview."\"><noscript><p>Zum Anzeigen der Karte wird Javascript benötigt.</p></noscript></div>
					</body>
				</html>";


}
?>

