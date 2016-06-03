<?php
/******************************************************************************
Parameter:
	gpx=[Reviername] (*.gpx Datei ohne Endung)
	user=[Innerhalb des Reviers eindeutiger Name des Jägers]
	lat=[latitude] (Positionsdaten des Jägers)
	lon=[longitude] (Positionsdaten des Jägers)
	ele/alt=[altitude] (Positionsdaten des Jägers)
	cmt=[Comment] (Kommentar)
	desc=[Desctription] (Beschreibungstext)
Aufruf: gpx.php?gpx=[Reviername]:[Ansicht]
******************************************************************************/
session_start();

$gpx_folder = "./reviere/";
//print_r($_REQUEST);
if(!$gpx = $_REQUEST["gpx"]){
		if(!$gpx = $_SESSION['revier']){
				exit();
		}
}
if(!$usr = $_REQUEST["user"]){
	exit();
}
//var_dump($_REQUEST);
if(!$_REQUEST["lat"] || !$_REQUEST["lat"]) {
	$edit_form = "<html>";
	$edit_form .= "<head>";
	$edit_form .= " <title>Edit " .$usr ."</title>";
	$edit_form .= " <meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />";
	$edit_form .= " <script type=\"text/javascript\">";
	$edit_form .= "		function calculatePosition() {";
	$edit_form .= "			var iLat = document.getElementById(\"lat\");";
	$edit_form .= "			var iLon = document.getElementById(\"lon\");";
	$edit_form .= "			if (navigator.geolocation) {";
	$edit_form .= "				navigator.geolocation.getCurrentPosition(getPosition, getError);";
	$edit_form .= "		  } else { ";
	$edit_form .= "				ausgabe.innerHTML = 'Ihr Browser unterstützt keine Geolocation.';";
	$edit_form .= "			}";
	$edit_form .= "		}";
	$edit_form .= "		";
	$edit_form .= "		function getPosition(position) {";
	$edit_form .= "			document.getElementById(\"lat\").value = position.coords.latitude;";
	$edit_form .= "			document.getElementById(\"lon\").value = position.coords.longitude;";
	$edit_form .= "			document.getElementById(\"editWpt\").submit();";
	$edit_form .= "		}";
	$edit_form .= "		function getError(error) {";
	$edit_form .= "   	var frm = document.getElementById(\"editWpt\");";
	$edit_form .= "   	var iLat = document.getElementById(\"lat\");";
	$edit_form .= "   	var iLon = document.getElementById(\"lon\");";
	$edit_form .= "			iLat.type = \"text\";";
	$edit_form .= "			iLon.type = \"text\";";
	$edit_form .= "			var submit = document.createElement(\"input\");";
	$edit_form .= "			var att = document.createAttribute(\"name\");";
	$edit_form .= "			att.value = \"submit\";";
	$edit_form .= "			submit.setAttributeNode(att);";
	$edit_form .= "			att = document.createAttribute(\"type\");";
	$edit_form .= "			att.value = \"submit\";";
	$edit_form .= "			submit.setAttributeNode(att);";
	$edit_form .= "			frm.appendChild(submit);";
	$edit_form .= "		}";
	$edit_form .= " </script>";
	$edit_form .= "</head>";
	$edit_form .= "<body onLoad=\"calculatePosition();\">";
	$edit_form .= "<form id=\"editWpt\">";
	$edit_form .= "	<input type=\"hidden\" name=\"gpx\" value=\"" .$gpx ."\" />";
	$edit_form .= "	<input type=\"hidden\" name=\"user\" value=\"" .$usr ."\" />";
	$edit_form .= "	<input type=\"hidden\" name=\"lat\" id=\"lat\" value=\"\" />";
	$edit_form .= "	<input type=\"hidden\" name=\"lon\" id=\"lon\" value=\"\" />";
	$edit_form .= "	<input type=\"hidden\" name=\"cmt\" value=\"&lt;a href=\"./edit.php?user=" .urlencode($usr) ." target=\"_blank\"&gt;move to current position&lt;/a&gt;\" />";
	$edit_form .= "</form>";
	$edit_form .= "</body>";
	$edit_form .= "</html>";
	exit($edit_form);
}


$gpx_file = $gpx_folder.$gpx.".gpx";
$gpx_user_file = $gpx_folder.$gpx.".user.gpx";
if(!file_exists($gpx_file)) exit();

$xml = simplexml_load_file($gpx_file);
if(file_exists($gpx_user_file)) {
	$user_xml = simplexml_load_file($gpx_user_file);
}
else {
	$user_xml = new SimpleXMLElement("<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<gpx
	version=\"1.0\"
	xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
	xmlns=\"http://www.topografix.com/GPX/1/0\"
	xsi:schemaLocation=\"http://www.topografix.com/GPX/1/0 http://www.topografix.com/GPX/1/0/gpx.xsd\"></gpx>");
}
if($_REQUEST["user"]) {
	if(!$lat = $_REQUEST["lat"]) {
		$pnt = $xml->metadata[0]->bounds[0];
		if($pnt) {
			$attrib = $pnt->attributes();
			$diff = (string)$attrib[0] - (string)$attrib[2];
			$sub = $diff / 2;
			$lat = (string)$attrib[0] - $sub;
//	var_dump($pnt);
//	var_dump($lat);
		}
		else {
			$pnt = $xml->trk[0]->trkseg[0]->trkpt[0]->attributes();
			$lat = $pnt[0];
		}
	}
	if(!$lon = $_REQUEST["lon"]) {
		$pnt = $xml->metadata[0]->bounds[0];
		if($pnt) {
			$attrib = $pnt->attributes();
			$diff = (string)$attrib[1] - (string)$attrib[3];
			$sub = $diff / 2;
			$lon = (string)$attrib[1] - $sub;
//	var_dump($pnt);
//	var_dump($lon);
		}
		else {
			$pnt = $xml->trk[0]->trkseg[0]->trkpt[0]->attributes();
			$lon = $pnt[1];
		}
	}
	$edited = false;
	for($i=0; $i<count($user_xml->wpt); ++$i) {
		if($user_xml->wpt[$i]->name == $_REQUEST["user"]) {
			//echo "found";
			$attr = "lat";
			$user_xml->wpt[$i]->attributes()->$attr = $lat;
			$attr = "lon";
			$user_xml->wpt[$i]->attributes()->$attr = $lon;
			if($ele = $user_xml->wpt[$i]->children("ele")) {
				if($_REQUEST["alt"]) {
					$user_xml->wpt[$i]->ele = $_REQUEST["alt"];
				}
				else if($_REQUEST["ele"]) {
					$user_xml->wpt[$i]->ele = $_REQUEST["ele"];
				}
			}
			if($_REQUEST["cmt"]) {
				$user_xml->wpt[$i]->cmt = $_REQUEST["cmt"];
				$user_xml->wpt[$i]->desc = $_REQUEST["cmt"];
			}
			else if($_REQUEST["desc"]) {
				$user_xml->wpt[$i]->cmt = $_REQUEST["desc"];
				$user_xml->wpt[$i]->desc = $_REQUEST["desc"];
			}
			else{
				$cmt = $user_xml->wpt[$i]->children("cmt");
				if(!$cmt || substr($user_xml->wpt[$i]->cmt, 0, 3) == "Lat") {
					$user_xml->wpt[$i]->cmt = "Lat: ".$_REQUEST["lat"]." Lon: ".$_REQUEST["lon"];
					$user_xml->wpt[$i]->desc = "Lat: ".$_REQUEST["lat"]." Lon: ".$_REQUEST["lon"];
				}
			}
			//print_r($user_xml->wpt[$i]->asXML());
			$edited = true;
		}
		$usertoadd = $xml->addChild("wpt");
		foreach($user_xml->wpt[$i]->attributes() as $n => $v) {
			$usertoadd->addAttribute($n, $v);
         } 
		foreach($user_xml->wpt[$i]->children() as $n => $v) {
			$usertoadd->addChild($n, $v);
         } 
	}
	if($edited == false) {
		$curUser = $user_xml ->addChild("wpt");
		$curUser->addChild("name", $_REQUEST["user"]);
		$curUser->addChild("sym", "hunter");
		$curUser->addAttribute("lat", $lat);
		$curUser->addAttribute("lon", $lon);
		if($_REQUEST["alt"]) {
			$curUser->addChild("ele", $_REQUEST["alt"]);
		}
		else if($_REQUEST["ele"]) {
			$curUser->addChild("ele", $_REQUEST["ele"]);
		}
		if($_REQUEST["cmt"]) {
			$curUser->addChild("cmt", $_REQUEST["cmt"]);
			$curUser->addChild("desc", $_REQUEST["cmt"]);
		}
		else if($_REQUEST["desc"]) {
			$curUser->addChild("cmt", $_REQUEST["desc"]);
			$curUser->addChild("desc", $_REQUEST["desc"]);
		}
		else{
			$curUser->addChild("cmt", "Lat: ".$_REQUEST["lat"]." Lon: ".$_REQUEST["lon"]);
		}
		$usertoadd = $xml->addChild("wpt");
		foreach($user_xml->attributes() as $n => $v) {
			$usertoadd->addAttribute($n, $v);
         } 
		foreach($user_xml->children() as $n => $v) {
			$usertoadd->addChild($n, $v);
         } 
	}
}
else {
	for($i=0; $i<count($user_xml->wpt); ++$i) {
		$usertoadd = $xml->addChild("wpt");
		foreach($user_xml->wpt[$i]->attributes() as $n => $v) {
			$usertoadd->addAttribute($n, $v);
         } 
		foreach($user_xml->wpt[$i]->children() as $n => $v) {
			$usertoadd->addChild($n, $v);
         } 
	}
}

if(is_writable($gpx_user_file)){
	exit("Scheisse!");
}
else{
	if (!$handle = fopen($gpx_user_file, "wb")) {
		exit("Grosse Scheisse: kann " .$gpx_user_file ." nicht oeffnen!");
	}
	
	// Schreibe $somecontent in die geöffnete Datei.
	if (!fwrite($handle, $user_xml->asXML())) {
		exit("Groesste Scheisse!");
	}
	
	fclose($handle);
}
/*
if($user_xml->asXML($gpx_user_file)){
	//print_r(realpath($gpx_user_file));
	header('Content-Type: text/xml'); 
	echo $user_xml->asXML();
}
else{
	if(!unlink(realpath($gpx_user_file))){
		echo "Voll doof!";
	}
	clearstatcache(true, $gpx_user_file);
	if(!$user_xml->asXML(realpath($gpx_user_file))){
		echo realpath($gpx_user_file) ." not saved!";
	}
}
*/

function appendXML($xElement, $appendElement) {
	if ($appendElement) {
		if (strlen(trim((string) $appendElement))==0) {
			$xml = $xElement->addChild($appendElement->getName());
			foreach($appendElement->children() as $child) {
				$xml = appendXML($xml, $child);
			}
		} else {
			$xml = $xElement->addChild($appendElement->getName(), (string) $appendElement);
		}
		foreach($appendElement->attributes() as $n => $v) {
			$xml->addAttribute($n, $v);
		}
	}
	return $xElement;
} 
?>