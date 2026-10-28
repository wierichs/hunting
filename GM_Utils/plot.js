// plot
// Version vom 4. 9. 2015
// Jürgen Berkemeier
// www.j-berkemeier.de

"use strict";

window.JB = window.JB || {};
JB.plot = function(feld,xstr,ystr) {
	this.ticwidth=1;
	this.linewidth=1;
	this.borderwidth=2;
	this.framecol = "black";
	this.gridcol = "gray";
	this.labelcol = "black";
	this.markercol = "black";
	this.fillopac = 0;
	this.xscale60 = false;
	var JB_makediv = function(parentnode,id,x,y,w,h) {
		var ele = document.createElement("div");
		ele.style.position = "absolute";
		if(typeof id == "string" && id.length) ele.id = id;
		if(typeof x == "number") ele.style.left = x + "px";
		if(typeof y == "number") ele.style.top = y + "px";
		if(typeof w == "number") ele.style.width = w + "px";
		if(typeof h == "number") ele.style.height = h + "px";
		parentnode.appendChild(ele);
		return ele;
	}
	if(typeof feld == "string") feld = document.getElementById(feld);
	var xobj = xstr?xstr:"x";
	var yobj = ystr?ystr:"y";
	var xmin=0,xmax=0,ymin=0,ymax=0;
	var xfak=0,yfak=0;
	var dx,dy,fx,fy;
	var gr = null;
	var xlabel = null;
	var ylabel = null;
	var w = parseInt(feld.offsetWidth-1);
	var h = parseInt(feld.offsetHeight-1);
	var marker;
	var ifeld = JB_makediv(feld,"","","",w,h);

	this.scale = function(a) {
		if(xmin==xmax) {
			xmax = xmin = a[0][xobj];
			ymax = ymin = a[0][yobj];
		}
		for(var i=1;i<a.length;i++) {
			var t = a[i];
			if(t[xobj]<xmin) xmin = t[xobj];
			if(t[xobj]>xmax) xmax = t[xobj];
			if(t[yobj]<ymin) ymin = t[yobj];
			if(t[yobj]>ymax) ymax = t[yobj];
		}
		if(this.xscale60) dx = (xmax - xmin)/50;
		else dx = (xmax - xmin)/100;		
		xmin -= dx; xmax += dx;
	} // plot.scale
	this.clear = function() {
		ifeld.innerHTML = "";
		xmax = xmin = ymax = ymin = xfak = yfak = 0;
	} // plot.clear
	this.frame = function(x0,y0,xl,yl) {
		ifeld.innerHTML = "";
		var cp = JB_makediv(ifeld,"",0,0,10,10);
		cp.innerHTML = "<a href='http://www.j-berkemeier.de' title='Plot 4. 9. 2015'>X</a>";
		cp.style.zIndex = "100";
		cp.style.opacity = "0";
		this.pele = JB_makediv(ifeld,"",x0,0,w-x0,h-y0);
		if(xl.length) xlabel=new JB.gra(JB_makediv(ifeld,"",x0,h-y0,w-x0,y0));
		if(yl.length) ylabel=new JB.gra(JB_makediv(ifeld,"",0,0,x0,h-y0));
		gr=new JB.gra(this.pele);
		gr.setbuf(1000);
		if(xl.length) xlabel.text(xlabel.w/2,0,".9em",this.labelcol,xl,"mu"); 
		if(yl.length) ylabel.text(10,ylabel.h/2,".9em",this.labelcol,yl,"mm");
		if(xmax==xmin) { xmin -= 0.5; xmax += 0.5; }
		dx = xmax - xmin;
		gr.setwidth(this.ticwidth);
		fx = Math.pow(10,Math.floor(JB.log10(dx))-1);
		xmin = Math.floor(xmin/fx)*fx;
		xmax = Math.ceil(xmax/fx)*fx;
		xfak = gr.w/(xmax-xmin);
		var tx = 100*dx/gr.w; 
		if(this.xscale60 && tx<.75) {
			var vz;
			     if(tx<0.02) tx = 1/60;
			else if(tx<0.04) tx = 1/30;
			else if(tx<0.1)  tx = 1/12;
			else if(tx<0.2)  tx = 1/6;
			else if(tx<0.4)  tx = 1/3;
			else             tx = 1/2;
			var mxmin = Math.ceil(xmin/tx)*tx;
			for(var x=mxmin;x<=xmax;x+=tx) {
				var xx = (x-xmin)*xfak;
				vz = "";
				if(x>=0) {
					var xh = Math.floor(x);
					var xm = Math.round((x - xh) * 60);
				}
				else {
					var xh = Math.ceil(x);
					var xm = Math.round((xh - x) * 60);
					if(xh==0 && xm!=0) vz = "-";
				}
				if(xm == 60) { xm = 0; xh++; }
				if(xm<10) xm = "0"+xm;
				var xln = vz+xh+"h"+xm+"'";
				gr.linie(xx,0,xx,gr.h,this.gridcol);
				if(xl.length && xx<(gr.w-5) && xx>5) xlabel.text(xx,xlabel.h-2,".8em",this.labelcol,xln,"mo");
			}
		}
		else {
			tx = JB.ticdist(tx);
			var mxmin = Math.ceil(xmin/tx)*tx;
			for(var x=mxmin;x<=xmax;x+=tx) {
				var xx = (x-xmin)*xfak;
				gr.linie(xx,0,xx,gr.h,this.gridcol);
				if(xl.length && xx<(gr.w-5) && xx>5) xlabel.text(xx,xlabel.h-2,".8em",this.labelcol,JB.toString(x),"mo");
			}
		}
		if(ymax==ymin) { ymin -= 0.5; ymax += 0.5; }
		dy = (ymax - ymin)/100; ymin -= dy; ymax += dy;
		dy = ymax - ymin;
		fy = Math.pow(10,Math.floor(JB.log10(dy))-1);
		ymin = Math.floor(ymin/fy)*fy;
		ymax = Math.ceil(ymax/fy)*fy;
		yfak = gr.h/(ymax-ymin);
		var ty = JB.ticdist(gr.h<250 ?  50*dy/gr.h : 100*dy/gr.h);
		var mymin = Math.ceil(ymin/ty)*ty;
		for(var y=mymin;y<=ymax;y+=ty) {
			var yy = (y-ymin)*yfak;
			gr.linie(0,yy,gr.w,yy,this.gridcol);
			if(yl.length && yy<(gr.h-5) && yy>5) ylabel.text(ylabel.w-2,yy,".8em",this.labelcol,JB.toString(y),"rm");
		}
		gr.setwidth(this.linewidth);
		var rahmen=new JB.gra(this.pele);
		rahmen.setwidth(this.borderwidth);
		rahmen.linie(       0,       0,rahmen.w,       0,this.framecol);
		rahmen.linie(rahmen.w,       0,rahmen.w,rahmen.h,this.framecol);
		rahmen.linie(rahmen.w,rahmen.h,       0,rahmen.h,this.framecol);
		rahmen.linie(       0,rahmen.h,       0,       0,this.framecol);
		this.mele = JB_makediv(ifeld,"",x0,0,w-x0,h-y0);
		if(gr.canvas) this.mele.style.backgroundColor = "rgba(255,255,255,0)"; // für den IE9 RC, ohne kein "onmouseover" etc.
	} // plot.frame
	this.plot = function(a,col) {
		var arr=[];
		for(var i=0,l=a.length;i<l;i++)
			arr.push({x:(a[i][xobj]-xmin)*xfak, y:(a[i][yobj]-ymin)*yfak});
		if(this.fillopac>0) {
			var y0;
			if(ymax*ymin<=0) y0 = -ymin*yfak ; 
			else if(ymin>0) y0 = 1;
			else y0 = h-1;
			arr.push({x:(a[l-1][xobj]-xmin)*xfak,y:y0});
			arr.push({x:(a[0][xobj]-xmin)*xfak,y:y0});
			arr.push({x:(a[0][xobj]-xmin)*xfak,y:(a[0][yobj]-ymin)*yfak});
			gr.polyfill(arr,col,this.fillopac);
			arr.length -= 3;
		}
		gr.polyline(arr,col);
		gr.flush();
	} // plot.plot)
	this.showmarker = function(markertype) {
		if(markertype=="Punkt") {
			marker = JB_makediv(this.pele,"","","","","");
			marker.style.fontSize = "32px";
			var txt=document.createTextNode(String.fromCharCode(8226)) ; // Kreis als Zeichen: &bull; oder &#8226; evtl auch 8729
			marker.appendChild(txt);
		}
		else {
			marker = JB_makediv(this.pele,"","",0,1,gr.h);
			marker.style.backgroundColor = this.markercol;
		}
		marker.style.display = "none";
	} // plot.showmarker
	this.hidemarker = function() {
		marker.style.display = "none";
	} // plot.hidemarker
	this.setmarker = function(a,markertype) {
		marker.style.display = "";
		if(markertype=="Punkt") {
			marker.style.left = Math.round((a[xobj]-xmin)*xfak) - marker.offsetWidth/2 + "px";
			marker.style.top = Math.round(gr.h - (a[yobj]-ymin)*yfak) - marker.offsetHeight/2 + "px";
		}
		else {
			marker.style.left = Math.round((a[xobj]-xmin)*xfak) + "px";
		}
	} // plot.setmarker
	this.markeron = function(a,callback_over,callback_out,callback_move,callback_click,markertype) {
		var dieses = this;
		var posx=0,offx;
		this.mele.onmouseover = this.mele.ontouchstart = function(e) {
			dieses.mele.click(); 
			if(!e) e = window.event;
			e.cancelBubble = true;
			if (e.stopPropagation) e.stopPropagation();
			var feldt = dieses.mele;
			var pi=0,al;
			offx = 0;
			if(feldt.offsetParent) 
				do {
					offx += feldt.offsetLeft;
				} while(feldt = feldt.offsetParent);
			if(callback_over && typeof(callback_over)=="function") callback_over();
			dieses.mele.onmousemove = dieses.mele.ontouchmove = function(e) {
				if(!e) e = window.event;
				e.cancelBubble = true;
				if(e.stopPropagation) e.stopPropagation();
				if(e.targetTouches && e.targetTouches[0] && e.targetTouches[0].clientX) posx = e.targetTouches[0].clientX;
				else if(e.pageX) posx = e.pageX;
				else if(e.clientX) posx = e.clientX + document.body.scrollLeft + document.body.clientLeft;
				posx -= offx;
				pi = dieses.getPolylinePos(posx,a);
				dieses.setmarker(a[pi],markertype);
				if(callback_move && typeof(callback_move)=="function") callback_move(pi,a[pi]);
				return false;
			}
			dieses.mele.onclick = dieses.mele.ontouch = function(e) {
				if(!e) e = window.event;
				e.cancelBubble = true;
				if(e.stopPropagation) e.stopPropagation();
				if(e.targetTouches && e.targetTouches[0] && e.targetTouches[0].clientX) posx = e.targetTouches[0].clientX;
				else if(e.pageX) posx = e.pageX;
				else if(e.clientX) posx = e.clientX + document.body.scrollLeft + document.body.clientLeft;
				posx -= offx;
				pi = dieses.getPolylinePos(posx,a);
				dieses.setmarker(a[pi],markertype);
				if(callback_click && typeof(callback_click)=="function") callback_click(pi,a[pi]);
				return false;
			}
			document.onkeydown = function(e) {
				if(!e) e = window.event;
				if(e.keyCode && (e.keyCode==37 || e.keyCode==39)) { 
					e.cancelBubble = true;
					if (e.stopPropagation) e.stopPropagation();
					if(e.keyCode==37) { pi--; if(pi<0) pi=0; }
					if(e.keyCode==39) { pi++; if(pi>=al) pi=al-1; }
					dieses.setmarker(a[pi],markertype);
					if(callback_move && typeof(callback_move)=="function") callback_move(pi,a[pi]);
					return false;
				}
				else if(e.keyCode && e.keyCode==13) {
					dieses.setmarker(a[pi],markertype);
					if(callback_click && typeof(callback_click)=="function") callback_click(pi,a[pi]);
					return false;
				}
				return true;
			}
			return false;
		} 
		this.mele.onmouseout = this.mele.ontouchend = function(e) {
			if(!e) e = window.event;
			document.onkeydown = null;
			dieses.mele.onclick = dieses.mele.ontouch = dieses.mele.onmousemove = dieses.mele.ontouchmove = null;
			dieses.hidemarker();
			if(callback_out && typeof(callback_out)=="function") callback_out();
			return false;
		}
	} // plot.markeron
	this.markeroff = function() {
		this.mele.onmousemove = this.mele.ontouchmove = null;
		this.mele.onmouseout = this.mele.ontouchend = null;
	} // plot.markeroff
	this.getPolylinePos =  function(posx,a) {
		var x = posx/xfak+xmin;
		var al = a.length;
		var p,pi;
		if(x<=a[0][xobj]) pi=0;
		else if(x>=a[al-1][xobj]) pi=al-1;
		else {
			p = al/2;
			pi = Math.floor(p);
			var dp = Math.ceil(p/2);
			do {
				var apx = a[pi][xobj];
				if(x<apx) { p -= dp; if(p<0) p=0; }
				else if(x>apx) { p += dp; if(p>al-1) p=al-1; }
				else break;
				pi = Math.floor(p);
				dp = dp/2;
			} while(dp>=0.5) ;
		}
		return pi;
	} // plot.getPolylinePos
} // plot
JB.farbbalken = function(ele) {
	this.create = function(r,o,u,farbtafel,ymin,ymax,yl) {
		this.fbdiv = document.createElement("div");
		this.fbdiv.style.width = "30px";
		this.fbdiv.style.position = "absolute";
		this.fbdiv.style.right = (50 + r) + "px";
		this.fbdiv.style.top = o + "px";
		this.fbdiv.style.bottom = u + "px";
		this.fbdiv.style.backgroundColor = "blue";
		this.fbdiv.style.zIndex = "1";
		ele.appendChild(this.fbdiv);
		this.fb = new JB.gra(this.fbdiv);
		this.fb.setwidth(2);
		for(var i=0;i<this.fb.h;i++)
			this.fb.hor_linie(0,this.fb.w,i,farbtafel[Math.floor(i*farbtafel.length/this.fb.h)]);
		var lbu = Math.max(0,u-6);
		var lbo = Math.max(0,o-6);
		var yoff = u - lbu;
		this.lbdiv = document.createElement("div");
		this.lbdiv.style.position = "absolute";
		this.lbdiv.style.right = r + "px";
		this.lbdiv.style.top = lbo + "px";
		this.lbdiv.style.bottom = lbu + "px";
		this.lbdiv.style.width = "50px";
		try { this.lbdiv.style.backgroundColor = "rgba(255,255,255,.2)"; } catch(e) { this.lbdiv.style.backgroundColor = "rgb(200,200,200)"; };
		this.lbdiv.style.zIndex = "1";
		ele.appendChild(this.lbdiv);
		this.lb = new JB.gra(this.lbdiv);
		var dy = ymax - ymin;
		var fy = Math.pow(10,Math.floor(JB.log10(dy))-1);
		ymin = Math.floor(ymin/fy)*fy;
		ymax = Math.ceil(ymax/fy)*fy;
		var yfak = this.fb.h/(ymax-ymin);
		var ty = JB.ticdist(this.fb.h<250 ?  50*dy/this.fb.h : 100*dy/this.fb.h);
		var mymin = Math.ceil(ymin/ty)*ty;
		var n_off = 3 + Math.max(this.lb.getTextWidth(JB.toString(ymin),"0.8em"),this.lb.getTextWidth(JB.toString(ymax),"0.8em"));
		for(var y=mymin;y<=ymax;y+=ty) {
			var yy = (y-ymin)*yfak+yoff;
			if(yy<(this.lb.h-5) && yy>5 ) {
				this.lb.text(n_off,yy,".8em","black",JB.toString(y),"rm");
				this.lb.text(n_off+10,this.lb.h/2,".9em","black",yl,"mm");
			}
		}
	}
	this.del = function() {
		if(this.fb) {
			this.fb.del();
			this.lb.del();
			this.fb = null;
			this.lb = null;
			ele.removeChild(this.fbdiv);
			ele.removeChild(this.lbdiv);
			this.fbdiv = null;
			this.lbdiv = null;
		}
	}
} // farbbalken
JB.ticdist = function(td) {
	var td10 = Math.pow(10,Math.floor(JB.log10(td)));
	td = Math.round(td/td10);
	td = Number(String(td).replace(/3/,"2").replace(/[4567]/,"5").replace(/[89]/,"10"));
	td *= td10;
	return td;
} // ticdist
JB.log10 = function(x) { return Math.log(x)/Math.LN10; }
JB.toString = function(n) { return Math.abs(n)<1e-15?"0":Number(n.toPrecision(15)).toString(10); }