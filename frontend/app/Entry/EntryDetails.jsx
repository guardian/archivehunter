import React from 'react';
import PropTypes from 'prop-types';
import EntryPreview from './EntryPreview.jsx';
import EntryThumbnail from './EntryThumbnail.jsx';
import FileSizeView from './FileSizeView.jsx';
import EntryJobs from "./EntryJobs.jsx";
import axios from 'axios';
import EntryLightboxBanner from "./EntryLightboxBanner";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import MediaDurationComponent from "../common/MediaDurationComponent.jsx";
import ErrorViewComponent, {formatError} from "../common/ErrorViewComponent.jsx";
import {createStyles, withStyles} from "@material-ui/core";
import MetadataTable from "./details/MetadataTable";
import LightboxInsert from "./details/LightboxInsert";

const styles = (theme)=>createStyles({

});

class EntryDetails extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired,
        autoPlay: PropTypes.boolean,
        showJobs: PropTypes.boolean,
        loadJobs: PropTypes.boolean,
        lightboxedCb: PropTypes.func,
        userLogin: null,
        lastError: null,

        preLightboxInsert: PropTypes.object,
        postLightboxInsert: PropTypes.object,
        postJobsInsert: PropTypes.object,
        tableRowsInsert: PropTypes.object,
        onError: PropTypes.func.isRequired
    };

    constructor(props){
        super(props);
        this.state = {
            loading: false,
            lastError: null,
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
                this.setState({lastError: err})
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
                props.onError(formatError(err));
                this.setState({loading: false, lastError: err});
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
            return <div className="entry-details">
            </div>
        }
        return <div className="entry-details" style={{overflowX: "scroll"}}>
                <EntryPreview entryId={this.props.entry.id}
                              hasProxy={this.props.entry.proxied}
                              fileExtension={this.props.entry.file_extension}
                              mimeType={this.props.entry.mimeType}
                              autoPlay={this.props.autoPlay}
                              triggeredProxyGeneration={this.proxyGenerationWasTriggered}
                />
            <div className="entry-details-insert">{ this.props.preLightboxInsert ? this.props.preLightboxInsert : "" }</div>
            <div className="entry-details-lightboxes" style={{display: this.props.entry.lightboxEntries.length>0 ? "block":"none"}}>
                <span style={{display:"block", marginBottom: "0.4em"}}><FontAwesomeIcon icon="lightbulb" style={{paddingRight: "0.4em"}}/>Lightboxes</span>
                <EntryLightboxBanner lightboxEntries={this.props.entry.lightboxEntries} entryClassName="entry-lightbox-banner-entry-large"/>
            </div>
            <div className="entry-details-insert">{ this.props.postLightboxInsert ? this.props.postLightboxInsert : "" }</div>
            {
                this.props.showJobs ? <EntryJobs entryId={this.props.entry.id}
                                                 loadImmediate={this.props.loadJobs}
                                                 autoRefresh={this.state.jobsAutorefresh}
                                                 autoRefreshUpdated={this.jobsAutorefreshUpdated}/> : ""
            }
            <div className="entry-details-insert">{ this.props.postJobsInsert ? this.props.postJobsInsert : "" }</div>
            <div className="entry-details-insert">
                <LightboxInsert isInLightbox={this.isInLightbox()} entryId={this.state.entry.id} onError={this.props.onError} lightboxedCb={this.props.lightboxedCb}/>
            </div>
            <div className="entry-details-insert">
                <MetadataTable entry={this.state.entry}/>
            </div>
            <p className="information">
                <a onClick={this.triggerAnalyse} style={{cursor: "pointer"}}>Refresh metadata</a>
            </p>
            {
                this.state.lastError ? <ErrorViewComponent error={this.state.lastError}/> : <span/>
            }
        </div>
    }
}

export default withStyles(styles)(EntryDetails);