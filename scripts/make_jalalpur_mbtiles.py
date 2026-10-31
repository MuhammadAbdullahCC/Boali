#!/usr/bin/env python3
"""Render a small REAL OSM-derived raster street map for Boali.

Uses OpenStreetMap VECTOR geometry via Overpass, not bulk raster tile scraping.
Data: © OpenStreetMap contributors, ODbL 1.0 (openstreetmap.org/copyright).
"""
import json,math,sqlite3,time,urllib.request,urllib.parse
from collections import Counter,defaultdict
from io import BytesIO
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

BBOX=(32.030,73.535,32.105,73.640) # south west north east: western Hafizabad district
CENTER=(32.067,73.587)
OUTPUT=Path("dist/Boali-Hafizabad-West-Offline-Streets.mbtiles")
HOSTS=[
  "https://overpass-api.de/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter",
  "https://overpass.private.coffee/api/interpreter",
  "https://overpass.nchc.org.tw/api/interpreter",
]
def get_osm():
    s,w,n,e=BBOX
    bbox=f"{s},{w},{n},{e}"
    q=("[out:json][timeout:120];("
       f'way["highway"]({bbox});'
       f'way["railway"]({bbox});'
       f'way["waterway"]({bbox});'
       f'way["landuse"]({bbox});'
       f'way["building"]({bbox});'
       f'node["place"]({bbox});'
       f'node["amenity"]({bbox});'
       ");out geom;")
    failures=[]
    for host in HOSTS:
        for attempt in range(2):
            try:
                print("Fetching real OSM geometry:",host,attempt+1,flush=True)
                payload=urllib.parse.urlencode({"data":q}).encode()
                req=urllib.request.Request(host,payload,headers={
                    "User-Agent":"BoaliOfflineBuilder/1.0 (+https://github.com/MuhammadAbdullahCC/Boali)",
                    "Accept":"application/json"
                },method="POST")
                with urllib.request.urlopen(req,timeout=145) as r:
                    result=json.loads(r.read(45*1024*1024))
                elements=result.get("elements",[])
                roads=sum(1 for el in elements if el.get("tags",{}).get("highway"))
                print("Objects:",len(elements),"real highway ways:",roads,flush=True)
                if roads<25: raise RuntimeError(f"Too few actual road ways: {roads}")
                return elements,host,roads
            except Exception as ex:
                failures.append(repr(ex))
                print("Source failed:",repr(ex),flush=True)
                if attempt==0:time.sleep(4)
    raise RuntimeError("No real OSM data; refusing to invent roads. "+str(failures[-5:]))
def merc(lon,lat,z):
    n=256*(1<<z);lat=max(-85.05,min(85.05,lat))
    a=math.radians(lat)
    return ((lon+180)/360*n,(1-math.asinh(math.tan(a))/math.pi)/2*n)
def tile(lon,lat,z):
    a,b=merc(lon,lat,z)
    return int(math.floor(a/256)),int(math.floor(b/256))
def parse(objects):
    out=[];counts=Counter()
    for ob in objects:
        tags=ob.get("tags",{})
        typ=next((k for k in ("landuse","building","waterway","railway","highway","place","amenity") if k in tags),None)
        if not typ:continue
        if ob.get("type")=="node":
            if "lat" not in ob:continue
            pts=[(float(ob["lon"]),float(ob["lat"]))]
        else:
            pts=[(float(g["lon"]),float(g["lat"])) for g in ob.get("geometry",[]) if "lon" in g and "lat" in g]
        if not pts:continue
        name=(tags.get("name:en") or tags.get("name") or "")[:55]
        if any(ord(c)>127 for c in name) and "name:en" not in tags:name=""
        out.append((typ,tags[typ],name,pts));counts[typ]+=1
    print("Objects by category:",dict(counts),flush=True)
    return out,counts
def width(t,z):
    scale={"motorway":1.9,"trunk":1.7,"primary":1.5,"secondary":1.4,
        "tertiary":1.2,"residential":1.0,"unclassified":1.0,"service":.72,
        "footway":.6,"path":.6,"steps":.6,"track":.8,"living_street":.85}
    base={12:2,13:2.5,14:3,15:4,16:5,17:7,18:10}[z]
    return max(1,round(base*scale.get(t,.9)))
def font(size,bold=False):
    import os
    for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
              "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"]:
        if os.path.exists(p):return ImageFont.truetype(p,size)
    return ImageFont.load_default()
def index(features,z,limit):
    x0,x1,y0,y1=limit
    indexed=[];buckets=defaultdict(list)
    for typ,val,name,pts in features:
        if z<15 and typ=="building":continue
        if z<14 and typ=="amenity":continue
        if z<13 and typ=="highway" and val in ("footway","path","steps","track","service"):continue
        xy=[merc(lo,la,z) for lo,la in pts]
        if not xy:continue
        ix0=max(x0,int(math.floor(min(p[0] for p in xy)/256)))
        ix1=min(x1,int(math.floor(max(p[0] for p in xy)/256)))
        iy0=max(y0,int(math.floor(min(p[1] for p in xy)/256)))
        iy1=min(y1,int(math.floor(max(p[1] for p in xy)/256)))
        if ix0>ix1 or iy0>iy1:continue
        k=len(indexed);indexed.append((typ,val,name,xy))
        for a in range(ix0,ix1+1):
            for b in range(iy0,iy1+1):buckets[(a,b)].append(k)
    return indexed,buckets
def make_tile(tx,ty,z,indexed,indices,regular,bold):
    im=Image.new("RGB",(256,256),(249,246,242));d=ImageDraw.Draw(im)
    layers=defaultdict(list)
    for i in indices:
        typ,val,name,xy=indexed[i]
        layers[typ].append((val,name,xy))
    def pts(seq):return [(round(x-tx*256),round(y-ty*256)) for x,y in seq]
    for val,name,poly in layers["landuse"]:
        if len(poly)>2:
            tint=(228,239,220) if val in ("forest","grass","meadow","farmland","orchard","park","garden") else (235,232,224)
            d.polygon(pts(poly),fill=tint)
    if z>=16:
        for _,_,poly in layers["building"]:
            if len(poly)>2:
                d.polygon(pts(poly),fill=(226,222,220))
                d.line(pts(poly+[poly[0]]),fill=(214,208,207),width=1)
    for _,_,poly in layers["waterway"]:
        if len(poly)>1:d.line(pts(poly),fill=(145,198,231),width=max(1,z-11),joint="curve")
    for _,_,poly in layers["railway"]:
        if len(poly)>1:d.line(pts(poly),fill=(166,154,146),width=max(1,round((z-10)/2)),joint="curve")
    roads=sorted(layers["highway"],key=lambda a:width(a[0],z))
    for val,_,poly in roads:
        if len(poly)>1:d.line(pts(poly),fill=(192,175,183),width=width(val,z)+2,joint="curve")
    for val,_,poly in roads:
        if len(poly)>1:
            color=(255,212,149) if val in ("motorway","trunk","primary") else (
                (255,240,180) if val in ("secondary","tertiary") else
                ((228,219,211) if val in ("footway","path","track") else (255,254,252)))
            d.line(pts(poly),fill=color,width=width(val,z),joint="curve")
    if z>=16:
        count=0
        for val,name,poly in roads:
            if not name or len(poly)<2 or count>=10:continue
            xx,yy=poly[len(poly)//2];xx-=tx*256;yy-=ty*256
            if not(20<xx<230 and 15<yy<236):continue
            if z==16 and val in ("service","footway","path","track"):continue
            if d.textbbox((0,0),name,font=regular)[2]>155:continue
            d.text((xx,yy),name,font=regular,anchor="mm",fill=(71,58,72),
                stroke_width=2,stroke_fill=(255,255,251));count+=1
    if z>=14:
        for val,name,poly in layers["place"]+(layers["amenity"] if z>=17 else []):
            if not name:continue
            xx,yy=poly[0];xx-=tx*256;yy-=ty*256
            if not(10<xx<240 and 10<yy<240):continue
            if val in ("city","town","village","suburb","hamlet"):
                d.text((xx,yy),name,font=bold,anchor="mm",fill=(101,48,112),
                    stroke_width=3,stroke_fill=(255,255,252))
            elif z>=17:
                d.ellipse((xx-2,yy-2,xx+2,yy+2),fill=(159,99,160))
                d.text((xx+5,yy-4),name,font=regular,fill=(87,73,100),
                    stroke_width=2,stroke_fill=(255,255,251))
    return im
def main():
    objects,host,roads=get_osm()
    features,counts=parse(objects)
    if counts["highway"]<25:raise RuntimeError("Map has insufficient real road data")
    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    if OUTPUT.exists():OUTPUT.unlink()
    db=sqlite3.connect(OUTPUT)
    db.executescript("""CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT);
    CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB,
    PRIMARY KEY(zoom_level,tile_column,tile_row));""")
    s,w,n,e=BBOX
    meta={
        "name":"Hafizabad West · Boali Offline Streets",
        "type":"baselayer","version":"1.0.0","format":"png",
        "description":"Real OpenStreetMap roads, buildings and places, locally rendered for offline walking",
        "attribution":"© OpenStreetMap contributors, ODbL 1.0",
        "bounds":f"{w},{s},{e},{n}","center":f"{CENTER[1]},{CENTER[0]},16",
        "minzoom":"12","maxzoom":"18","scheme":"tms",
        "source":"OpenStreetMap geometry via "+host}
    db.executemany("INSERT INTO metadata VALUES(?,?)",meta.items())
    small=font(9);heading=font(13,True)
    total=0
    for z in range(12,19):
        x0,y0=tile(w,n,z);x1,y1=tile(e,s,z)
        listed,buckets=index(features,z,(x0,x1,y0,y1))
        print(f"Zoom {z}: {(x1-x0+1)*(y1-y0+1)} tiles",flush=True)
        for y in range(y0,y1+1):
            for x in range(x0,x1+1):
                im=make_tile(x,y,z,listed,buckets.get((x,y),[]),small,heading)
                buffer=BytesIO();im.save(buffer,"PNG",compress_level=7)
                db.execute("INSERT INTO tiles VALUES(?,?,?,?)",
                    (z,x,(1<<z)-1-y,buffer.getvalue()))
                total+=1
            if (y-y0)%20==0:db.commit()
        db.commit()
    assert total>1000, f"Too few tiles: {total}"
    assert db.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
    assert db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]==total
    db.close()
    print("SUCCESS",total,"tiles",OUTPUT.stat().st_size,"bytes",flush=True)
if __name__=="__main__":main()
