import React from "react";
import PropTypes from "prop-types";
import axios from "axios";
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";

class PathCacheAdmin extends React.Component {
    static propTypes = {

    }

    constructor(props) {
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            cachedPathCount: 0,
            reindexWaiting: false,
            reindexSuccessful: false
        };

        this.requestReindex = this.requestReindex.bind(this);
    }

    updatePathCount() {
        this.setState({loading: true}, ()=>axios.get("/api/pathcache/size")
            .then(response=>{
                this.setState({loading: false, lastError: null, cachedPathCount: response.data.count})
            })
            .catch(err=>{
                console.error("Could not get current path count: ", err);
                this.setState({loading: false, lastError: err, cachedPathCount: 0})
            })
        );
    }

    componentDidMount() {
        this.updatePathCount();
    }

    requestReindex() {
        this.setState({reindexWaiting: true, reindexSuccessful: false}, ()=>axios.put("/api/pathcache/rebuild")
            .then(response=>{
                this.setState({reindexWaiting: false, reindexSuccessful: true})
            })
            .catch(err=>{
                console.error("Could not request reindex: ", err);
                this.setState({reindexWaiting: false, reindexSuccessful: false, lastError: err})
            })
        );
    }

    render() {
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            {
                this.state.lastError ? <ErrorViewComponent error={this.state.err}/> :
                    <div>
                        {this.state.loading ?
                            <p className="centered">Loading...</p> :
                            <p className="centered">There are currently {this.state.cachedPathCount} cached path fragments</p>
                        }
                        <p className="centered">You can rebuild the index here. It's not blanked before use.  The process should take less than half an hour.</p>
                        <button onClick={this.requestReindex} disabled={this.state.reindexSuccessful || this.state.reindexWaiting}>Re-index</button>
                        {
                            this.state.reindexWaiting ? <p>Waiting....</p> : null
                        }
                        {
                            this.state.reindexSuccessful ? <p>Re-index has been started, you can close this page</p> : null
                        }
                    </div>
            }
        </div>
    }
}

export default PathCacheAdmin;
