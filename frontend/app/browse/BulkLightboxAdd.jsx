import React from 'react';
import PropTypes from 'prop-types';
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import axios from 'axios';
import ErrorViewComponent, {formatError} from "../common/ErrorViewComponent.jsx";
import BytesFormatter from "../common/BytesFormatter.jsx";
import {Button, CircularProgress} from "@material-ui/core";
import {WbIncandescent} from "@material-ui/icons";

class BulkLightboxAdd extends React.Component {
    static propTypes = {
        searchDoc: PropTypes.object.isRequired,
        onError: PropTypes.func
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            quotaExceeded: false,
            quotaRequired: -1,
            quotaLevel: -1,
            bulkRecord: null
        };

        this.triggerBulkLightboxing = this.triggerBulkLightboxing.bind(this);
    }

    componentDidMount() {
        this.checkBulkRecord();
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        console.log("componentDidUpdate: ", this.props.collection, this.props.path);
        if(prevProps.collection !== this.props.collection || prevProps.path !== this.props.path) this.checkBulkRecord();
    }

    /**
     * query the server to see if we are already saved as a bulk
     */
    checkBulkRecord() {
        this.setState({loading: true},
            ()=>axios.put("/api/lightbox/my/bulk/query", this.props.searchDoc, {headers: {"Content-Type": "application/json"}})
                .then(response=>{
                    this.setState({loading: false, bulkRecord: response.data.entry});
                }).catch(err=>{
                    console.error(err);
                    if(this.props.onError) this.props.onError(formatError(err, false));
                    this.setState({loading: false})
                })
        )
    }


    triggerBulkLightboxing() {
        if(this.state.loading) return;
        this.setState({loading: true},
            ()=>axios.put("/api/lightbox/my/addFromSearch", this.props.searchDoc,{headers:{"Content-Type":"application/json"}}).then(response=>{
                console.log(response.data);
                this.setState({loading:false, bulkRecord: response.data.objectId ? response.data.objectId : null});
            }).catch(err=>{
                if(err.response && err.response.status===413){
                    console.log(err.response.data);
                    if(this.props.onError) {
                        this.props.onError("Can't lightbox this as it would exceed your restore quota.")
                    }
                    this.setState({
                        loading: false,
                        quotaExceeded: true,
                        quotaRequired: err.response.data.requiredQuota,
                        quotaLevel: err.response.data.actualQuota
                    })
                } else {
                    if(this.props.onError) this.props.onError(formatError(err, false));
                    this.setState({loading: false});
                }
            })
        )
    }

    //return an icon name based on the component state.
    iconForState() {
        if(this.state.loading) return "redo-alt";
        if(this.state.bulkRecord) return "check";
        return "lightbulb";
    }

    // render(){
    //     return <div className="centered" style={{marginTop: "0.1em", paddingLeft: "0.5em", display: this.props.path ? "inline":"none"}}>
    //         {
    //             this.state.quotaExceeded ? <span><p><FontAwesomeIcon icon="lightbulb" className="button-icon"/>Can't add as this would exceed your quota. You would need <BytesFormatter value={this.state.quotaRequired*1048576}/> but only have <BytesFormatter value={this.state.quotaLevel*1048576}/>.</p></span> : ""
    //         }
    //         {
    //             this.state.quotaExceeded || this.state.bulkRecord ? "" : <span>
    //                 <FontAwesomeIcon icon={this.iconForState()} className={this.state.loading ? "button-icon spin" : "button-icon"}/>
    //                 <a style={{cursor: "pointer"}} onClick={this.triggerBulkLightboxing}>Lightbox All</a>
    //             </span>
    //         }
    //         {
    //             this.state.bulkRecord ? <span>
    //                 <FontAwesomeIcon icon={this.iconForState()} className={this.state.loading ? "button-icon spin" : "button-icon"}/>
    //                 <a>Saved to lightbox</a>
    //             </span> : ""
    //         }
    //         {
    //             this.state.lastError ? <span className="error-text" style={{marginLeft: "0.6em"}}>Server error, not all items were added</span> : ""
    //         }
    //     </div>
    // }
    render() {
        return <>
            {
                this.state.loading ? <Button disabled={true} startIcon={<CircularProgress/>} variant="contained">Lightboxing...</Button> :
                    <Button startIcon={<WbIncandescent/>} variant="outlined">Lightbox all</Button>
            }
            </>
    }
}

export default BulkLightboxAdd;