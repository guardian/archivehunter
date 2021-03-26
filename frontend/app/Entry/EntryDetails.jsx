import React from 'react';
import PropTypes from 'prop-types';
import EntryJobs from "./EntryJobs.jsx";
import axios from 'axios';
import {formatError} from "../common/ErrorViewComponent.jsx";
import {Button, createStyles, Grid, IconButton, Typography, withStyles} from "@material-ui/core";
import MetadataTable from "./details/MetadataTable";
import LightboxInsert from "./details/LightboxInsert";
import {baseStyles} from "../BaseStyles";
import MediaPreview from "./MediaPreview";

const styles = (theme)=>Object.assign(createStyles({
    entryDetails: {
        overflow: "auto",
        height: "100%",
    },
    entryDetailsLightboxes: {
        marginLeft: "1.5em"
    },
    partialDivider: {
        width: "70%"
    },
    centeredMedia: {
        marginLeft: "auto",
        marginRight: "auto",
        width: "100%",
        textAlign: "center",
    }
}), baseStyles);

class EntryDetails extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired,
        autoPlay: PropTypes.bool,
        showJobs: PropTypes.bool,
        loadJobs: PropTypes.bool,
        lightboxedCb: PropTypes.func,
        userLogin: null,

        preLightboxInsert: PropTypes.object,
        postLightboxInsert: PropTypes.object,
        postJobsInsert: PropTypes.object,
        tableRowsInsert: PropTypes.object,
        onError: PropTypes.func.isRequired,
        openClicked: PropTypes.func
    };

    constructor(props){
        super(props);
        this.state = {
            loading: false,
            jobsAutorefresh: false,
            lightboxSaving: false,
        };

        this.jobsAutorefreshUpdated = this.jobsAutorefreshUpdated.bind(this);
        this.proxyGenerationWasTriggered = this.proxyGenerationWasTriggered.bind(this);
        this.triggerAnalyse = this.triggerAnalyse.bind(this);
    }

    componentDidMount(){
        this.setState({loading: true}, ()=>axios.get("/api/loginStatus")
            .then(response=> {
                this.setState({userLogin: response.data})
            }).catch(err=>{
                console.error(err);
                props.onError(formatError(err));
            }));
    }

    jobsAutorefreshUpdated(newValue){
        this.setState({jobsAutorefresh: newValue});
    }

    proxyGenerationWasTriggered(){
        console.log("new proxy generation was started");
        this.setState({jobsAutorefresh: true});
    }

    triggerAnalyse(){
        this.setState({loading: true}, ()=>axios.post("/api/proxy/analyse/" + this.props.entry.id)
            .then(response=>{
                console.log("Media analyse started");
                this.setState({loading: false, jobsAutorefresh: true});
            }).catch(err=>{
                console.error(err);
                if(this.props.onError) this.props.onError(formatError(err));
                this.setState({loading: false});
            }));
    }

    componentDidUpdate(oldProps,oldState, snapshot){
        if (oldProps.entry !== this.props.entry && this.state.jobsAutorefresh) this.setState({jobsAutorefresh: false});
    }

    isInLightbox(){
        const matchingEntries = this.props.entry.lightboxEntries.filter(lbEntry=>lbEntry.owner===this.state.userLogin.email);
        return matchingEntries.length>0;
    }

    render(){
        if(!this.props.entry){
            return <div className={this.props.classes.entryDetails}>
            </div>
        }
        return <div className={this.props.classes.entryDetails}>
                <MediaPreview itemId={this.props.entry.id}
                              itemName={this.props.entry.path}
                              fileExtension={this.props.entry.file_extension}
                              mimeType={this.props.entry.mimeType}
                              autoPlay={this.props.autoPlay}
                              relinkedCb={this.props.lightboxedCb}  //use the lightboxCb to indicate that the item needs reloading
                              triggeredProxyGeneration={this.proxyGenerationWasTriggered}
                              className={this.props.classes.centeredMedia}
                />
            <div className="entry-details-insert">{ this.props.preLightboxInsert ? this.props.preLightboxInsert : "" }</div>
            <div className={this.props.classes.entryDetailsLightboxes}>
                <LightboxInsert isInLightbox={this.isInLightbox()}
                                entryId={this.props.entry.id}
                                onError={this.props.onError}
                                lightboxedCb={this.props.lightboxedCb}
                                lightboxEntries={this.props.entry.lightboxEntries}
                />
            </div>
            <hr className={this.props.classes.partialDivider}/>
            <div className="entry-details-insert">{ this.props.postLightboxInsert ? this.props.postLightboxInsert : "" }</div>
            {
                this.props.showJobs ? <EntryJobs entryId={this.props.entry.id}
                                                 loadImmediate={this.props.loadJobs}
                                                 autoRefresh={this.state.jobsAutorefresh}
                                                 autoRefreshUpdated={this.jobsAutorefreshUpdated}/> : ""
            }
            <div className="entry-details-insert">{ this.props.postJobsInsert ? this.props.postJobsInsert : "" }</div>
            <div className="entry-details-insert">
                <MetadataTable entry={this.props.entry} openClicked={this.props.openClicked}/>
            </div>
            <div className={this.props.classes.centered} style={{marginTop: "1em"}}>
                <Button variant="outlined" onClick={this.triggerAnalyse}>Re-analyse metadata</Button>
            </div>
        </div>
    }
}

export default withStyles(styles)(EntryDetails);