import React from 'react';
import axios from 'axios';
import PropTypes from 'prop-types';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import LoadingThrobber from '../common/LoadingThrobber.jsx';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import ThreeWayIcon from "./ThreeWayIcon.jsx";

class AttemptRetry extends React.Component {
    static propTypes = {
        itemId: PropTypes.string.isRequired,
        haveVideo: PropTypes.bool,
        haveAudio: PropTypes.bool,
        haveThumb: PropTypes.bool
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            done: {
                audio: false,
                video: false,
                thumb: false
            },
            completed: false,
            lastError:null
        };

        this.retryClicked = this.retryClicked.bind(this);
    }

    doProxy(proxyType, haveFlag){
        if(haveFlag){
            return new Promise((resolve,reject)=>resolve());
        } else {
            const actualProxyType = (proxyType==="thumb") ? "thumbnail" : proxyType.toUpperCase();
            return new Promise((resolve,reject)=>axios.post("/api/proxy/generate/" + this.props.itemId + "/" + actualProxyType).then(result=>{
                let update ={};
                update[proxyType] = true;
                this.setState({done: Object.assign(this.state.done,update)}, ()=>resolve());
            }).catch(err=>{
                console.error(err);
                this.setState({lastError: err}, ()=>reject(err))
            }));
        }
    }

    retryClicked(){
        this.setState({loading: true, lastError:null},()=>{
            this.doProxy("video", this.props.haveVideo)
                .then(this.doProxy("audio", this.props.haveAudio))
                .then(this.doProxy("thumb",this.props.haveThumb))
                .then(finalResult=>{
                    this.setState({loading: false, completed: true})
                })
                .catch(err=>{
                    console.error(err);
                    this.setState({loading: false, completed: true})
                })
        });
    }

    render(){
        return <span>
            <LoadingThrobber show={this.state.loading} small={true} inline={true}/>
            <ThreeWayIcon iconName="film" state={this.state.done.video} onColour="green" hide={! (this.state.loading || this.state.completed)}/>
            <ThreeWayIcon iconName="volume-up" state={this.state.done.audio} onColour="green" hide={! (this.state.loading || this.state.completed)}/>
            <ThreeWayIcon iconName="image" state={this.state.done.thumb} onColour="green" hide={! (this.state.loading || this.state.completed)}/>
            <a onClick={this.retryClicked} className="clickable" style={{display: this.state.loading ? "none" : "inline"}}>Attempt retry...</a>
        </span>
    }
}

export default AttemptRetry;