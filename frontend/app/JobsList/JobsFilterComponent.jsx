import React from 'react';
import PropTypes from 'prop-types';
import omit from 'lodash.omit';
import ItemEntryName from "../common/ItemEntryName.jsx";
import ClickableIcon from "../common/ClickableIcon.jsx";
import RefreshButton from "../common/RefreshButton.jsx";
import {Grid, MenuItem, Select, createStyles, withStyles, InputLabel, Button} from "@material-ui/core";
import {Refresh} from "@material-ui/icons";

const styles = (theme)=>createStyles({
    selector: {
        minWidth: "200px",
        maxWidth: "400px"
    },
    rightAlignedButton: {
        marginLeft:"auto",
        marginTop: "auto"
    }
});

class JobsFilterComponent extends React.Component {
    static propTypes = {
        activeFilters: PropTypes.object.isRequired,
        filterChanged: PropTypes.func.isRequired,
        refreshClicked: PropTypes.func.isRequired,
        isLoading: PropTypes.bool.isRequired
    };

    constructor(props){
        super(props);

        this.selectorChanged = this.selectorChanged.bind(this);
        this.sourceIdRemoved = this.sourceIdRemoved.bind(this);
    }

    jobTypeFilter(){
        if(this.props.activeFilters.hasOwnProperty("jobType")){
            return this.props.activeFilters.jobType
        } else {
            return "proxy";
        }
    }

    statusFilter(){
        if(this.props.activeFilters.hasOwnProperty("jobStatus")){
            return this.props.activeFilters.jobStatus
        } else {
            return "";
        }
    }

    JOB_CHANGE=1;
    STATUS_CHANGE=2;

    selectorChanged(change, value){
        switch(change){
            case this.JOB_CHANGE:
                const updatedFilters = value!=="" ? Object.assign({}, this.props.activeFilters, {jobType: value}) : omit(this.props.activeFilters, "jobType");
                this.props.filterChanged(updatedFilters);
                break;
            case this.STATUS_CHANGE:
                const stUpdatedFilters = value!=="" ? Object.assign({}, this.props.activeFilters, {jobStatus: value}) : omit(this.props.activeFilters, "jobStatus");
                this.props.filterChanged(stUpdatedFilters);
                break;
            default:
                console.error("Got selector changed from unknown source: ", change)
        }
    }

    sourceIdRemoved(evt){
        const updatedFilters = omit(this.props.activeFilters, "sourceId");
        this.props.filterChanged(updatedFilters);
    }

    render() {
        return <Grid container direction="row" spacing={3}>
            <Grid item>
                <InputLabel shrink id="jobtype-selector-label">Job Type</InputLabel>
                <Select id="jobtype-selector"
                        onChange={(evt)=>this.selectorChanged(this.JOB_CHANGE, evt.target.value)}
                        value={this.jobTypeFilter()}
                        labelId="jobtype-selector-label"
                        className={this.props.classes.selector}
                >
                    <MenuItem value="proxy">Proxy</MenuItem>
                    <MenuItem value="thumbnail">Thumbnail</MenuItem>
                    <MenuItem value="RESTORE">Restore</MenuItem>
                    <MenuItem value="Analyse">Analyse</MenuItem>
                    <MenuItem value="CheckSetup">Check setup</MenuItem>
                    <MenuItem value="SetupTranscoding">Set up transcoding</MenuItem>
                    <MenuItem value="FileMove">File move</MenuItem>
                </Select>
            </Grid>
            <Grid item>
                <InputLabel shrink id="status-selector-label">Status</InputLabel>
                <Select id="status-selector"
                        onChange={(evt)=>this.selectorChanged(this.STATUS_CHANGE, evt.target.value)}
                        value={this.statusFilter()}
                        labelId="status-selector-label"
                        className={this.props.classes.selector}
                        inputProps={{"data-id":"status-selector"}}
                >
                    <MenuItem value="">(any)</MenuItem>
                    <MenuItem value="ST_PENDING">Pending</MenuItem>
                    <MenuItem value="ST_RUNNING">Running</MenuItem>
                    <MenuItem value="ST_SUCCESS">Success</MenuItem>
                    <MenuItem value="ST_ERROR">Error</MenuItem>
                </Select>
            </Grid>
            <Grid item style={{display: this.props.activeFilters.hasOwnProperty("sourceId") ? "inherit" : "none"}}>
                <ClickableIcon icon="times" onClick={this.sourceIdRemoved} style={{paddingRight: "0.4em"}}/>
                <label htmlFor="entry-filter" className="filter-control-label">Specific item:</label>
                <ItemEntryName id="entry-filter" showLink={true} entryId={this.props.activeFilters.hasOwnProperty("sourceId") ? this.props.activeFilters.sourceId : null}/>
            </Grid>
            <Grid item className={this.props.classes.rightAlignedButton}>
                <Button startIcon={<Refresh/>}
                        variant="outlined"
                        onClick={this.props.refreshClicked}>
                    Refresh
                </Button>
                {/*<RefreshButton*/}
                {/*    isRunning={this.props.isLoading}*/}
                {/*    clickedCb={this.props.refreshClicked}*/}
                {/*    showText={true} caption={this.props.isLoading ? "Loading..." : "Refresh"}/>*/}
            </Grid>
        </Grid>
    }
}

export default withStyles(styles)(JobsFilterComponent);