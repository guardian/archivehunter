import React from "react";
import {
    Grid,
    makeStyles,
    Typography,
    Input,
    Select,
    MenuItem
} from "@material-ui/core";

interface BrowseFilterProps {
    filterString: string;
    filterStringChanged: (newString:string)=>void;
    typeString: string;
    typeStringChanged: (newString:string)=>void;
}

const useStyles = makeStyles({
    selector: {
        width: "100%"
    }
});

const BrowseFilter:React.FC<BrowseFilterProps> = (props) => {
    const classes = useStyles();

    return <Grid container direction="column" >
        <Grid item>
            <Typography variant="h6">Filter</Typography>
        </Grid>
        <Grid item>
            <Input id="filter-input" value={props.filterString} onChange={(evt) => props.filterStringChanged(evt.target.value as string)} />
        </Grid>
        <Grid item style={{ marginTop: 8 }}>
            Type: <Select id="filter-type" value={props.typeString} onChange={(evt) => props.typeStringChanged(evt.target.value as string)} >
                <MenuItem value="Any">Any</MenuItem>
                <MenuItem value="application/gzip">application/gzip</MenuItem>
                <MenuItem value="application/javascript">application/javascript</MenuItem>
                <MenuItem value="application/json">application/json</MenuItem>
                <MenuItem value="application/msword">application/msword</MenuItem>
                <MenuItem value="application/mxf">application/mxf</MenuItem>
                <MenuItem value="application/octet-stream">application/octet-stream</MenuItem>
                <MenuItem value="application/pdf">application/pdf</MenuItem>
                <MenuItem value="application/photoshop">application/photoshop</MenuItem>
                <MenuItem value="application/postscript">application/postscript</MenuItem>
                <MenuItem value="application/psd">application/psd</MenuItem>
                <MenuItem value="application/rtf">application/rtf</MenuItem>
                <MenuItem value="application/x-7z-compressed">application/x-7z-compressed</MenuItem>
                <MenuItem value="application/x-cdf">application/x-cdf</MenuItem>
                <MenuItem value="application/x-gzip">application/x-gzip</MenuItem>
                <MenuItem value="application/x-photoshop">application/x-photoshop</MenuItem>
                <MenuItem value="application/x-tar">application/x-tar</MenuItem>
                <MenuItem value="application/xml">application/xml</MenuItem>
                <MenuItem value="application/zip">application/zip</MenuItem>
                <MenuItem value="audio/aac">audio/aac</MenuItem>
                <MenuItem value="audio/aiff">audio/aiff</MenuItem>
                <MenuItem value="audio/midi">audio/midi</MenuItem>
                <MenuItem value="audio/mpeg">audio/mpeg</MenuItem>
                <MenuItem value="audio/ogg">audio/ogg</MenuItem>
                <MenuItem value="audio/wav">audio/wav</MenuItem>
                <MenuItem value="audio/x-aiff">audio/x-aiff</MenuItem>
                <MenuItem value="audio/x-wav">audio/x-wav</MenuItem>
                <MenuItem value="binary/octet-stream">binary/octet-stream</MenuItem>
                <MenuItem value="image/bmp">image/bmp</MenuItem>
                <MenuItem value="image/gif">image/gif</MenuItem>
                <MenuItem value="image/jpeg">image/jpeg</MenuItem>
                <MenuItem value="image/png">image/png</MenuItem>
                <MenuItem value="image/psd">image/psd</MenuItem>
                <MenuItem value="image/tiff">image/tiff</MenuItem>
                <MenuItem value="image/vnd.adobe.photoshop">image/vnd.adobe.photoshop</MenuItem>
                <MenuItem value="image/x-icon">image/x-icon</MenuItem>
                <MenuItem value="text/css">text/css</MenuItem>
                <MenuItem value="text/csv">text/csv</MenuItem>
                <MenuItem value="text/html">text/html</MenuItem>
                <MenuItem value="text/plain">text/plain</MenuItem>
                <MenuItem value="text/richtext">text/richtext</MenuItem>
                <MenuItem value="text/xml">text/xml</MenuItem>
                <MenuItem value="video/mp4">video/mp4</MenuItem>
                <MenuItem value="video/mpeg">video/mpeg</MenuItem>
                <MenuItem value="video/ogg">video/ogg</MenuItem>
                <MenuItem value="video/quicktime">video/quicktime</MenuItem>
                <MenuItem value="video/webm">video/webm</MenuItem>
                <MenuItem value="video/x-msvideo">video/x-msvideo</MenuItem>
                <MenuItem value="video/x-sgi-movie">video/x-sgi-movie</MenuItem>
                <MenuItem value="video/x-flv">video/x-flv</MenuItem>
            </Select>
        </Grid>
    </Grid>
}

export default BrowseFilter;